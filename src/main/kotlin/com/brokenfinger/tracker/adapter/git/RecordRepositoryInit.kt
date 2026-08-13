package com.brokenfinger.tracker.adapter.git

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Makes the record repository exist (#258).
 *
 * `bootstrap.md` §2 used to be three commands the user ran before first start, and forgetting
 * `git init` was not an error: records were written and never committed, with a once-per-process
 * warning saying so. The server already writes into this directory, ignores inside it, commits
 * and pushes — creating it and running `git init` is a smaller act than any of those, so the
 * common case now simply works and the warning is left for the cases that still refuse.
 *
 * **An existing repository is untouched**, whatever its state. `--initial-branch=main` pins the
 * branch name rather than inheriting the machine's `init.defaultBranch`, because the push and
 * backup paths speak of one branch and a machine set to `master` would split the two.
 *
 * The #93 shape — a records directory nested inside some other project's repository — is not
 * refused but *repaired* by this: an own `.git` makes the directory its own root, and the
 * enclosing project can no longer be staged from it.
 *
 * Failure is logged, never thrown. A grading cannot be replayed (protocol §11), and the
 * not-a-repository warning downstream already describes the degraded state accurately.
 */
class RecordRepositoryInit(private val recordRoot: Path) {
    fun ensure() {
        runCatching { initialiseUnlessRepository() }.onFailure { warn(it) }
    }

    private fun initialiseUnlessRepository() {
        // Said on every boot, because the path may never have been chosen: compose defaults it,
        // and a default that lands data somewhere must say where (#258).
        logger.info("Records live at {}", recordRoot.toAbsolutePath())
        Files.createDirectories(recordRoot)
        if (isOwnRepository()) return
        init()
    }

    // The same question CommandLineGitSync asks, for the same reason (#93): `--git-dir` answers
    // about any enclosing repository, `--show-toplevel` compared to the root answers about ours.
    private fun isOwnRepository(): Boolean {
        val top = git("rev-parse", "--show-toplevel") ?: return false
        return runCatching { Path.of(top.trim()).toRealPath() == recordRoot.toRealPath() }.getOrDefault(false)
    }

    private fun init() {
        val done = git("init", "--initial-branch=main")
        if (done == null) {
            logger.warn("`git init` failed in {} — records will be written but never committed", recordRoot)
            return
        }
        logger.info("Initialised {} as a git repository on `main` — records will be committed here", recordRoot)
    }

    /** Output on success, null on failure — this class only needs the distinction. */
    private fun git(vararg args: String): String? {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(recordRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return output.takeIf { process.exitValue() == 0 }
    }

    private fun warn(cause: Throwable) {
        logger.warn(
            "Could not initialise the record repository at {} ({}). Records are still written; " +
                "run `git init` there yourself to keep a history.",
            recordRoot,
            cause.javaClass.simpleName,
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L

        val logger = LoggerFactory.getLogger(RecordRepositoryInit::class.java)
    }
}
