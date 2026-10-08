package com.brokenfinger.tracker.adapter.git

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One `git` invocation in [root], run the same way for every caller in this package (#360).
 *
 * Output goes to files rather than pipes, which is what makes [TIMEOUT] a real bound: a full pipe
 * buffer would block us before we ever got to wait. The two streams are kept apart, so what git
 * answers is read from stdout alone and a diagnosis reads both. Standard input is closed either way,
 * so no command can wait on it.
 *
 * Terminal prompting is off, so a push cannot stop for credentials — and the timeout is there for the
 * case where it stalls anyway, because a capture must never wait on the network. Replace refs are off
 * as well: with one in place the search read a clean replacement while the push sent the original
 * object, which a transfer always does.
 *
 * **The environment is the tracker's, whatever the server was started with ([inherited]).** Every
 * variable that moves git to another repository, working tree or index is removed, and so is every
 * switch that changes how a pathspec reads: the exclusions that keep `.ps` out are pathspec magic, and
 * `GIT_LITERAL_PATHSPECS=1` reads `:(exclude,glob,icase)[.]ps` as a file name (the review's ENV).
 */
internal class GitProcess(
    private val root: Path,
    private val inherited: Map<String, String> = System.getenv(),
    private val discard: (Path) -> Unit = { Files.deleteIfExists(it) },
) {
    /** Runs [command] — `git` and its arguments — and answers how it ended. Never waits past [TIMEOUT]. */
    fun run(command: List<String>, input: String? = null): GitResult =
        inTempFile(".out") { stdout -> inTempFile(".err") { stderr -> ran(command, input, stdout, stderr) } }

    private fun ran(command: List<String>, input: String?, stdout: Path, stderr: Path): GitResult {
        val code = exitCodeOf(command, input, stdout, stderr)
        return GitResult(code, textOf(stdout), textOf(stderr))
    }

    // Each file is deleted by its own `finally`, so a second that cannot be created leaves no first behind.
    private inline fun <T> inTempFile(suffix: String, block: (Path) -> T): T {
        val file = Files.createTempFile("git-", suffix)
        try {
            return block(file)
        } finally {
            discarded(file)
        }
    }

    // Windows will not delete a file a process still holds, and a git that has exited can leave a child
    // holding its output: `git push` to a local path runs receive-pack. The answer is read by then, so a
    // file that will not go is left for the JVM to try again at exit rather than costing the answer.
    private fun discarded(file: Path) {
        runCatching { discard(file) }.onFailure { file.toFile().deleteOnExit() }
    }

    private fun exitCodeOf(command: List<String>, input: String?, stdout: Path, stderr: Path): Int {
        val process = ProcessBuilder(command)
            .directory(root.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .also { it.environment().putAll(environment()) }
            .start()
        feed(process, input)
        if (process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) return process.exitValue()
        process.destroyForcibly()
        return TIMED_OUT
    }

    private fun environment(): Map<String, String> = inherited - UNSET + PINNED

    // A command that exits before reading its input closes the pipe; its exit code then says what happened.
    private fun feed(process: Process, input: String?) {
        runCatching { process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray()) } } }
    }

    // Decoded leniently: a path git stores in bytes that are not UTF-8 is read, never thrown on.
    private fun textOf(file: Path): String = String(Files.readAllBytes(file), StandardCharsets.UTF_8)

    companion object {
        /** Generous for a local repository, and short enough that nothing waits on it. */
        val TIMEOUT: Duration = Duration.ofSeconds(60)

        private val PINNED = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_NO_REPLACE_OBJECTS" to "1")

        private val UNSET = setOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_COMMON_DIR",
            "GIT_INDEX_FILE",
            "GIT_LITERAL_PATHSPECS",
            "GIT_GLOB_PATHSPECS",
            "GIT_NOGLOB_PATHSPECS",
            "GIT_ICASE_PATHSPECS",
        )
        private const val TIMED_OUT = -1
    }
}

/** One finished `git` invocation — its exit code, its answer on [stdout], and what it said on [stderr]. */
internal data class GitResult(val code: Int, val stdout: String, val stderr: String) {
    /** Everything git printed, for a diagnosis that must not depend on which stream git chose. */
    val output: String get() = stdout + stderr

    fun succeeded(): Boolean = code == 0

    // Git's own words when another process holds the index: "Unable to create
    // '<repo>/.git/index.lock': File exists." followed by "Another git process seems to be
    // running in this repository." Measured 2026-08-05 against git 2.48.1. Both spellings are
    // matched because the second survives a git that rewords the first.
    fun blockedByLock(): Boolean = output.contains(LOCK) || output.contains(ANOTHER_PROCESS)

    private companion object {
        const val LOCK = "index.lock"
        const val ANOTHER_PROCESS = "Another git process"
    }
}
