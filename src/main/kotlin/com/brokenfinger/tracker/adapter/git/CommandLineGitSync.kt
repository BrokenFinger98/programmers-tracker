package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.application.GitSync
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * [GitSync] over the `git` command line, run inside the record repository.
 *
 * The CLI rather than a library: the repository is the user's own, they open it in their own
 * editor, and every behaviour worth having here — the index lock, the pathspec commit, the
 * push refspec — is git's, not ours. A JGit dependency would reimplement that surface and
 * still have to interoperate with the git that other processes are running.
 *
 * **`index.lock` contention is expected, not corruption.** IntelliJ, a terminal or an
 * Obsidian git plugin can hold the index at any moment; the lock is not ours to own, so the
 * only sound posture is to back off and try again on a bounded schedule
 * ([[decisions/2026-08-05-write-serialization]] decision 4). Anything else — a rejected
 * push, a repository that is not there — fails at once instead, because retrying a failure
 * that cannot heal only pretends it might. Both end the same way: logged, never thrown, and
 * left for the next [reconcile].
 *
 * Waiting is injected so the retry schedule is testable without sleeping, the same shape
 * `CableChannelSubscriber` uses for reconnect.
 */
class CommandLineGitSync(
    private val root: Path,
    private val waitFor: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : GitSync {
    override fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean =
        neverThrowing("commit") { commitScoped(record, paths) }

    override fun reconcile(): Boolean = neverThrowing("reconcile") { commitEverything() }

    override fun push(): Boolean = neverThrowing("push") { pushed() }

    // A run owns no attempt number and no commit of its own (design §4.6); its edits to the
    // solution file ride along with the next submit or the next reconciliation.
    private fun commitScoped(record: SubmissionRecord, paths: List<Path>): Boolean {
        if (record.action != GradingAction.SUBMIT) return true
        val scope = insideRoot(paths)
        if (scope.isEmpty() || !isDirty(scope)) return true
        val message = CommitMessage.of(record)
        if (!retryingOnContention("commit") { stageAndCommit(scope, message) }) return false
        pushOnPass(record)
        return true
    }

    /**
     * The pass is the trigger, not the scope. `git push` moves the **whole branch**, so a
     * pass on one problem also pushes every commit pending for every other problem —
     * "never pushed until solved" describes when we push, never what goes up (design §4.6).
     *
     * A failed push is deliberately not a failed commit: the commit is the durable part, and
     * the daily backup run picks the push up later.
     */
    private fun pushOnPass(record: SubmissionRecord) {
        if (record.verdict != Verdict.PASS) return
        push()
    }

    private fun commitEverything(): Boolean {
        if (!isDirty(emptyList())) return true
        return retryingOnContention("reconcile") { stageAllAndCommit() }
    }

    private fun pushed(): Boolean {
        val result = git(listOf("push"))
        if (result.succeeded()) return true
        return failed("push", result)
    }

    // `git commit -- <paths>` is a partial commit: it takes those paths from the working tree
    // and ignores the rest of the index, which is what keeps another process's staged file out.
    private fun stageAndCommit(scope: List<String>, message: String): GitResult {
        val staged = git(listOf("add", "--") + scope)
        if (!staged.succeeded()) return staged
        return git(listOf("commit", "--message", message, "--") + scope)
    }

    private fun stageAllAndCommit(): GitResult {
        val staged = git(listOf("add", "--all"))
        if (!staged.succeeded()) return staged
        return git(listOf("commit", "--message", RECONCILE_MESSAGE))
    }

    /**
     * Retries [action] only while the index is locked by someone else, up to [MAX_ATTEMPTS]
     * attempts on the [BACKOFF_SCHEDULE]. Every other failure returns immediately.
     */
    private fun retryingOnContention(what: String, action: () -> GitResult): Boolean {
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = action()
            if (result.succeeded()) return true
            if (!result.blockedByLock()) return failed(what, result)
            if (attempt < MAX_ATTEMPTS) waitFor(backoffFor(attempt))
        }
        return abandoned(what)
    }

    /** Whether anything under [scope] differs from HEAD; an empty scope asks about everything. */
    private fun isDirty(scope: List<String>): Boolean =
        git(listOf("status", "--porcelain", "--") + scope).output.isNotBlank()

    private fun insideRoot(paths: List<Path>): List<String> = paths.mapNotNull { relativeOf(it) }.distinct()

    // Forward slashes on every host, as git wants them. A path that escapes the record
    // repository is dropped rather than staged: committing a file the user never meant to
    // publish is not a failure we get to make quietly.
    private fun relativeOf(path: Path): String? {
        val relative = root.toAbsolutePath().relativize(path.toAbsolutePath())
        if (relative.startsWith("..")) return outside()
        return relative.joinToString("/")
    }

    private fun outside(): String? {
        logger.warn("A path outside the record repository was not staged")
        return null
    }

    /** A git failure never fails a capture ([[decisions/2026-08-05-write-serialization]]). */
    private fun neverThrowing(what: String, action: () -> Boolean): Boolean =
        runCatching(action).getOrElse { crashed(what, it) }

    private fun crashed(what: String, cause: Throwable): Boolean {
        logger.warn("git {} could not run ({}) — left for the next reconciliation", what, cause.javaClass.simpleName)
        return false
    }

    // Logs git's own words: six months later "why is nothing committed" has to be answerable,
    // and the record repository holds no secret — its remote and paths are the user's own.
    private fun failed(what: String, result: GitResult): Boolean {
        logger.warn("git {} failed with {}: {}", what, result.code, result.output.trim())
        return false
    }

    private fun abandoned(what: String): Boolean {
        logger.warn("git {} gave up after {} attempts — the index stayed locked", what, MAX_ATTEMPTS)
        return false
    }

    private fun backoffFor(attempt: Int): Duration = BACKOFF_SCHEDULE.getOrElse(attempt - 1) { BACKOFF_SCHEDULE.last() }

    /**
     * One `git` invocation with stderr folded into the output, so a diagnosis never depends
     * on which stream git chose.
     *
     * Output goes to a file rather than a pipe, which is what makes [TIMEOUT] a real bound:
     * a full pipe buffer would block us before we ever got to wait. Terminal prompting is
     * off, so a push cannot stop for credentials — and the timeout is there for the case
     * where it stalls anyway, because a capture must never wait on the network.
     */
    private fun git(args: List<String>): GitResult {
        val output = Files.createTempFile("git-", ".out")
        try {
            return GitResult(exitCodeOf(args, output), Files.readString(output))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun exitCodeOf(args: List<String>, output: Path): Int {
        val process = ProcessBuilder(listOf(GIT) + args)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .also { it.environment()[NO_PROMPT] = "0" }
            .start()
        if (process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) return process.exitValue()
        process.destroyForcibly()
        return TIMED_OUT
    }

    companion object {
        /** What a reconciliation commit says: these files were left behind, not chosen. */
        const val RECONCILE_MESSAGE = "chore: reconcile uncommitted records"

        /**
         * Four retries and then the next reconciliation takes over. An external lock holder
         * is a human's editor committing, which holds the index for well under a second, so
         * a schedule this short covers the realistic case; a lock held longer than that is
         * not contention we can wait out inside one capture.
         */
        const val MAX_ATTEMPTS = 5

        val BACKOFF_SCHEDULE: List<Duration> = listOf(100L, 200L, 400L, 800L).map(Duration::ofMillis)

        /** Generous for a local repository, and short enough that nothing waits on it. */
        val TIMEOUT: Duration = Duration.ofSeconds(60)

        private const val GIT = "git"
        private const val NO_PROMPT = "GIT_TERMINAL_PROMPT"
        private const val TIMED_OUT = -1

        private val logger = LoggerFactory.getLogger(CommandLineGitSync::class.java)
    }
}

/** One finished `git` invocation — its exit code and everything it printed. */
private data class GitResult(val code: Int, val output: String) {
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
