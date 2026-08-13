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
 *
 * **A fresh install has a records directory and no repository at all.** That is a
 * configuration fact, not a transient failure: it is answered once, said once, and then every
 * git call is skipped for the lifetime of this instance. Failing each commit forever and
 * logging each one would bury every other message the tool has to say, and the records
 * themselves are written either way.
 */
class CommandLineGitSync(
    private val root: Path,
    private val waitFor: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : GitSync {
    /**
     * Carried on our own invocations rather than written into the repository's config, so the
     * user's working copy is left as they keep it (#267). See [PushCredential].
     */
    private val credential = PushCredential(root)

    /**
     * Asked once, on the first git call rather than at construction — the composition root
     * builds this before the user has any chance to fix it, and a lazy answer keeps the
     * message next to the work it stopped. `by lazy` is synchronized, so concurrent first
     * calls still ask once.
     */
    private val isRepository: Boolean by lazy { detectRepository() }

    override fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean =
        inRepository("commit") { commitScoped(record, paths) }

    override fun reconcile(): Boolean = inRepository("reconcile") { commitEverything() }

    override fun push(): Boolean = inRepository("push") { pushed() }

    // `git remote` lists names and prints nothing when there is none, so an empty answer is the
    // whole signal. A failure to run it answers false: unknown is not "configured".
    override fun hasRemote(): Boolean = git(listOf("remote")).let { it.succeeded() && it.output.isNotBlank() }

    private fun inRepository(what: String, action: () -> Boolean): Boolean {
        if (!isRepository) return false
        return neverThrowing(what, action)
    }

    // `rev-parse` is git's own answer and covers what a `.git` directory test does not — a
    // worktree whose `.git` is a file. But it must be `--show-toplevel` compared against the
    // root, not `--git-dir`: the latter succeeds from *any* subdirectory and answers about
    // the enclosing repository, so a records directory nested inside another project passed
    // this check, and `reconcile`'s repo-wide `add --all` then committed that project's
    // unrelated working tree under our message and pushed it (#93).
    private fun detectRepository(): Boolean {
        val top = runCatching { git(listOf("rev-parse", "--show-toplevel")) }.getOrNull()
        if (top != null && top.succeeded() && isRoot(top.output.trim())) return true
        logger.warn(NOT_A_REPOSITORY, root)
        return false
    }

    // Compared as real paths so a symlinked or `/private`-prefixed record root still matches
    // the toplevel git reports; an unreadable path simply is not the root.
    private fun isRoot(toplevel: String): Boolean =
        runCatching { Path.of(toplevel).toRealPath() == root.toRealPath() }.getOrDefault(false)

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

    /**
     * The exact argument list handed to the process, credential prefix included. Internal
     * because it is the wiring itself: [PushCredential] computing the right `-c` is worth
     * nothing if it never reaches a git call, and that is not observable from the outside.
     */
    internal fun commandFor(args: List<String>): List<String> = listOf(GIT) + credential.gitConfig() + args

    private fun exitCodeOf(args: List<String>, output: Path): Int {
        val process = ProcessBuilder(commandFor(args))
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

        /** Said once per process, so it stays readable instead of drowning every other line. */
        const val NOT_A_REPOSITORY =
            "{} is not a git repository, so records are written but never committed. " +
                "Run `git init` there and restart to keep a history — this is said only once."

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
