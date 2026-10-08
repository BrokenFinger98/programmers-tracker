package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.git.StoredCredential.Patterns
import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.application.GitSync
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * **Nothing it commits or pushes carries the push token (#360).** The token lives in `.ps/`,
 * inside the repository, and a clone or a pull can deliver what switches a single guard off — a
 * `.gitignore` git will not read, a link where a file was, a name the filesystem folds to `.ps`.
 * So the guards are layered, each covering what the one before cannot:
 *
 * 1. Reconciliation leaves `.ps/` out by pathspec, in any ASCII case, whatever `.gitignore`
 *    says ([RECONCILE_SCOPE]).
 * 2. Git runs only while `.ps` is the real state directory ([StateDirectory]): not a link a pull
 *    swapped in, and not a name the filesystem folds to it.
 * 3. The credential is replaced, never written through a link ([GithubRemote]), and git is only
 *    pointed at it while it is a regular file ([PushCredential]).
 * 4. The content gate: what is staged, before every commit, and every commit a push would send,
 *    before the push, are searched for the token itself. It is the last line, and the only one
 *    that does not depend on a path.
 *
 * Every refusal is one WARN that names why and never the token, and a false — fail closed.
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

    private val stateDirectory = StateDirectory(root, TrackedStateEntries(root))

    /**
     * Asked once, on the first git call rather than at construction — the composition root
     * builds this before the user has any chance to fix it, and a lazy answer keeps the
     * message next to the work it stopped. `by lazy` is synchronized, so concurrent first
     * calls still ask once.
     */
    private val isRepository: Boolean by lazy { detectRepository() }

    /** Whether git's ignore rules for `.ps/` were already asked about — once, like [isRepository]. */
    private val stateIgnoreAsked = AtomicBoolean()

    /** Whether waiting out the user's merge, cherry-pick, revert or rebase was already said. */
    private val waitingSaid = AtomicBoolean()

    override fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean =
        inRepository("commit") { commitScoped(record, paths) }

    override fun reconcile(): Boolean = inRepository("reconcile") { commitEverything() }

    override fun push(): Boolean = inRepository("push") { pushed() }

    // `git remote` lists names and prints nothing when there is none, so an empty answer is the
    // whole signal. A failure to run it answers false: unknown is not "configured".
    override fun hasRemote(): Boolean = git(listOf("remote")).let { it.succeeded() && it.stdout.isNotBlank() }

    private fun inRepository(what: String, action: () -> Boolean): Boolean {
        if (!isRepository) return false
        return neverThrowing(what) { inVerifiedState(what) && action() }
    }

    /**
     * Commits and pushes run only while `.ps` is the tracker's own state directory, with no link below
     * it and nothing below it tracked by git (#360). A link a pull swapped in, a name the filesystem
     * folds to `.ps`, a link inside it or a file git tracks there turns state the server writes into
     * a path a commit can carry — raw frames, timers, the credential. Checked on every call, because a
     * pull can change it while the server runs.
     */
    private fun inVerifiedState(what: String): Boolean = when (val inspection = stateDirectory.inspected()) {
        is StateDirectory.Usable -> true
        is StateDirectory.Refused -> refusedState(what, inspection.reason)
    }

    private fun refusedState(what: String, reason: String): Boolean {
        logger.warn(STATE_REFUSED, what, root, reason)
        return false
    }

    // `rev-parse` is git's own answer and covers what a `.git` directory test does not — a
    // worktree whose `.git` is a file. But it must be `--show-toplevel` compared against the
    // root, not `--git-dir`: the latter succeeds from *any* subdirectory and answers about
    // the enclosing repository, so a records directory nested inside another project passed
    // this check, and `reconcile`'s repo-wide `add --all` then committed that project's
    // unrelated working tree under our message and pushed it (#93).
    private fun detectRepository(): Boolean {
        val top = runCatching { git(listOf("rev-parse", "--show-toplevel")) }.getOrNull()
        if (top != null && top.succeeded() && isRoot(top.stdout.trim())) return true
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
        if (!committed("commit", scope, CommitMessage.of(record))) return false
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
        warnOnceUnlessStateIgnored()
        if (operationInProgress()) return waitingItOut()
        if (!isDirty(RECONCILE_SCOPE)) return true
        return committed("reconcile", RECONCILE_SCOPE, RECONCILE_MESSAGE)
    }

    /**
     * Says once per instance, on the first reconciliation, when git's own rules do not ignore
     * `.ps/` — a `.gitignore` without the rule, or one git cannot read, such as a link. The
     * pathspec keeps `.ps/` out of reconciliation either way; the warning is for every other rule
     * in that file, which stands or falls with it, and for every `git add` that is not ours.
     *
     * The question is the rules', so it is asked as `.ps/` and without the index. Asked as `.ps`,
     * git applies the directory rule `.ps/` only to a directory that exists, and a fresh repository
     * has none yet; asked of the index, a state file someone staged by hand reads as "not
     * ignored" because it is tracked. Each would report a working rule as broken.
     */
    private fun warnOnceUnlessStateIgnored() {
        if (stateIgnoreAsked.getAndSet(true)) return
        if (git(listOf("check-ignore", "--quiet", "--no-index", STATE_DIRECTORY)).code != NO_RULE_MATCHED) return
        logger.warn(STATE_NOT_IGNORED_WARNING, root)
    }

    /**
     * A merge, cherry-pick, revert or rebase the user has open. Git refuses a partial commit inside one
     * ("cannot do a partial commit during a merge"), and the staging before it would already have marked
     * every conflict resolved. Asked where git keeps its state, which in a worktree is not `.git/`.
     */
    private fun operationInProgress(): Boolean {
        val paths = git(listOf("rev-parse") + IN_PROGRESS.flatMap { listOf("--git-path", it) })
        if (!paths.succeeded()) return false
        return paths.stdout.lines().filter { it.isNotBlank() }.any { Files.exists(root.resolve(it.trim())) }
    }

    // Records stay uncommitted until the user finishes or aborts; the next reconciliation after that
    // picks them up. Said once, because the backup asks every minute while it is due.
    private fun waitingItOut(): Boolean {
        if (!waitingSaid.getAndSet(true)) logger.warn(OPERATION_IN_PROGRESS, root)
        return false
    }

    // The push's half of the gate: nothing is sent until every commit it would send was searched.
    private fun pushed(): Boolean {
        if (!carriesNoCredential("push") { outgoingSearches() }) return false
        val result = git(listOf("push"))
        if (result.succeeded()) return true
        return failed("push", result)
    }

    /**
     * Stages [scope], searches what is staged there for the push token, and commits exactly [scope].
     * `add` with a pathspec stages removals as well (git 2.0 and later), with or without `--all`.
     *
     * `git commit -- <paths>` is a partial commit: it takes those paths from the working tree and
     * ignores the rest of the index, which is what keeps another process's staged file out. On a
     * branch with no commit yet it is still a root commit (measured on git 2.48.1). It reads the
     * working tree again, so a file that changes between the search and the commit is committed
     * unsearched — which is why the push searches again, what was actually committed.
     */
    private fun committed(what: String, scope: List<String>, message: String): Boolean {
        if (!retryingOnContention(what) { git(listOf("add", "--all", "--") + scope) }) return false
        if (!carriesNoCredential(what) { listOf(listOf("--cached", "--") + scope) }) return false
        return retryingOnContention(what) { git(listOf("commit", "--message", message, "--") + scope) }
    }

    /**
     * The content gate (#360): true when the push token is in none of the searches — each the
     * arguments of one `git grep`, run with what the store holds as fixed strings on stdin, never in
     * argv. It searches for the token, not for a path, so it holds wherever the token turns up.
     *
     * Fails closed, with one WARN that never carries what was searched for: a store that is there and
     * cannot be read, searches that cannot be listed (null), a search that does not finish, a match.
     */
    private fun carriesNoCredential(what: String, searches: () -> List<List<String>>?): Boolean =
        when (val stored = credential.stored()) {
            StoredCredential.None -> true
            StoredCredential.Unreadable -> refused(CREDENTIAL_UNREADABLE, what)
            is StoredCredential.Patterns -> searchedClean(what, stored, searches())
        }

    private fun searchedClean(what: String, patterns: Patterns, searches: List<List<String>>?): Boolean {
        if (searches == null) return refused(CREDENTIAL_UNSEARCHED, what)
        val outcome = searches.asSequence().map { grep(patterns, it) }.firstOrNull { it != NO_MATCH } ?: return true
        if (outcome == MATCH) return refused(CREDENTIAL_FOUND, what)
        return refused(CREDENTIAL_UNSEARCHED, what)
    }

    private fun grep(patterns: Patterns, search: List<String>): Int =
        git(listOf("grep", "-q", "-F", "-f", "-") + search, patterns.asInput()).code

    /**
     * One search per batch of the commits a push would send. A commit no remote-tracking branch holds
     * is one the remote is not known to have — a push updates the branch it pushed, so after the first
     * one this is what was committed since. The tracker configures no upstream (`push.default=current`),
     * so `@{u}..HEAD` would name nothing; with no remote-tracking branch at all it is every commit.
     * Null when they cannot be listed.
     */
    private fun outgoingSearches(): List<List<String>>? {
        val listed = git(listOf("rev-list", "HEAD", "--not", "--remotes"))
        if (!listed.succeeded()) return null
        return listed.stdout.lines().filter { it.isNotBlank() }.chunked(REVISIONS_PER_SEARCH) { it + "--" }
    }

    /** One WARN naming why — never what was searched for, or where it matched — and false. */
    private fun refused(why: String, what: String): Boolean {
        logger.warn(why, what, root)
        return false
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

    /**
     * Whether anything under [scope] differs from HEAD, read from git's answer alone. A warning is
     * not a change: while `.gitignore` is a link, git warns on every call, and that warning once
     * made a clean tree look dirty and every reconciliation an empty commit that failed (#360). A
     * status that failed still counts as dirty, so the staging that follows runs and reports why.
     *
     * Untracked files are always listed: `status.showUntrackedFiles=no` is the user's setting, and
     * honoured here it made every new record look like nothing to reconcile (#360).
     */
    private fun isDirty(scope: List<String>): Boolean {
        val status = git(listOf("status", "--porcelain", "--untracked-files=all", "--") + scope)
        return !status.succeeded() || status.stdout.isNotBlank()
    }

    private fun insideRoot(paths: List<Path>): List<String> = paths.mapNotNull { relativeOf(it) }.distinct()

    // Forward slashes on every host, as git wants them. A path that escapes the record
    // repository is dropped rather than staged: committing a file the user never meant to
    // publish is not a failure we get to make quietly. So is one under the state directory, in any
    // case a filesystem may fold to it (#360): no record lives there.
    private fun relativeOf(path: Path): String? {
        val relative = root.toAbsolutePath().relativize(path.toAbsolutePath())
        if (relative.startsWith("..")) return notStaged("outside the record repository")
        if (relative.getName(0).toString().equals(StateDirectory.NAME, ignoreCase = true)) {
            return notStaged("under the state directory")
        }
        return relative.joinToString("/")
    }

    private fun notStaged(where: String): String? {
        logger.warn("A path {} was not staged", where)
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
     * One `git` invocation, its two streams kept apart: what git answers is read from stdout
     * alone, and a diagnosis reads both, so it never depends on which stream git chose.
     *
     * Output goes to files rather than pipes, which is what makes [TIMEOUT] a real bound:
     * a full pipe buffer would block us before we ever got to wait. Terminal prompting is
     * off, so a push cannot stop for credentials — and the timeout is there for the case
     * where it stalls anyway, because a capture must never wait on the network.
     */
    private fun git(args: List<String>, input: String? = null): GitResult =
        inTempFile(".out") { stdout -> inTempFile(".err") { stderr -> ran(args, input, stdout, stderr) } }

    private fun ran(args: List<String>, input: String?, stdout: Path, stderr: Path): GitResult {
        val code = exitCodeOf(args, input, stdout, stderr)
        return GitResult(code, Files.readString(stdout), Files.readString(stderr))
    }

    // Each file is deleted by its own `finally`, so a second that cannot be created leaves no first behind.
    private inline fun <T> inTempFile(suffix: String, block: (Path) -> T): T {
        val file = Files.createTempFile("git-", suffix)
        try {
            return block(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * The exact argument list handed to the process, credential prefix included. Internal
     * because it is the wiring itself: [PushCredential] computing the right `-c` is worth
     * nothing if it never reaches a git call, and that is not observable from the outside.
     */
    internal fun commandFor(args: List<String>): List<String> = listOf(GIT) + credential.gitConfig() + args

    private fun exitCodeOf(args: List<String>, input: String?, stdout: Path, stderr: Path): Int {
        val process = ProcessBuilder(commandFor(args))
            .directory(root.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .also { it.environment()[NO_PROMPT] = "0" }
            .start()
        feed(process, input)
        if (process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) return process.exitValue()
        process.destroyForcibly()
        return TIMED_OUT
    }

    // Standard input is closed either way, so no command can wait on it. A command that exits before
    // reading it closes the pipe; its exit code then says what happened.
    private fun feed(process: Process, input: String?) {
        runCatching { process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray()) } } }
    }

    companion object {
        /** What a reconciliation commit says: these files were left behind, not chosen. */
        const val RECONCILE_MESSAGE = "chore: reconcile uncommitted records"

        /**
         * Everything but what is under `.ps/`, the tracker's own state — the push token is
         * `.ps/git-credentials`. The check, the staging and the commit all take this pathspec, so
         * nothing under `.ps/` enters a reconciliation whatever `.gitignore` says, or whether git
         * can read it at all (#360).
         *
         * **Spelled as a glob on purpose.** `add --all` exits 1 when an argument names a path the
         * ignore rules exclude, and an exclusion counts as naming it: with `.ps/` ignored, as in a
         * healthy repository, `:(exclude).ps`, `:!.ps` and every other spelling that starts with
         * `.ps` failed each reconciliation (measured on git 2.48.1). git judges whether an
         * argument names a path by its prefix before the first wildcard (`exclude_matches_pathspec`
         * in git's `dir.c`); `[.]` leaves this one no prefix, and still matches nothing but the dot.
         *
         * **And in any ASCII case** (`icase`), so `.PS/` is left out too (#360). On a case-insensitive
         * volume a case alias of `.ps` is refused before git runs ([StateDirectory]); this is what holds
         * where both spellings can stand side by side. ASCII only — `.p` with U+017F, which APFS folds
         * to `.ps`, is not matched, and the state-directory check and the content gate stand there.
         *
         * **The entry itself as well as what is under it.** A partial commit takes every path the
         * pathspec matches in HEAD too: with a tracked `.ps` link taken out of the index by hand and a
         * real directory in its place, it found a directory where a link was tracked and stopped —
         * "'.ps' does not have a commit checked out" (measured on 2.48.1 and 2.53.0).
         */
        private val RECONCILE_SCOPE = listOf(
            ".",
            ":(exclude,glob,icase)${StateDirectory.GLOB}",
            ":(exclude,glob,icase)${StateDirectory.GLOB}/**",
        )

        /** Said once per process, so it stays readable instead of drowning every other line. */
        const val NOT_A_REPOSITORY =
            "{} is not a git repository, so records are written but never committed. " +
                "Run `git init` there and restart to keep a history — this is said only once."

        private const val STATE_NOT_IGNORED_WARNING =
            "git does not ignore .ps/ in {}: its .gitignore lacks the rule, or git cannot read the " +
                "file — git never follows a .gitignore that is a symbolic link, and then none of its " +
                "rules (.DS_Store, editor state) apply. The tracker's own commits still leave .ps/ out, " +
                "and it commits or pushes nothing that carries the push token; another tool's git add " +
                "does not hold back. Make .gitignore a regular file that holds the rule. " +
                "This is said only once."

        /** The tracker's state directory, spelled with its slash so git knows it is a directory. */
        private const val STATE_DIRECTORY = "${StateDirectory.NAME}/"

        private const val OPERATION_IN_PROGRESS =
            "{} has a merge, cherry-pick, revert or rebase in progress, so reconciliation waits rather " +
                "than commit inside it: records stay uncommitted until it is finished or aborted. " +
                "This is said only once."

        /** Where git marks an operation the user has open, as `git rev-parse --git-path` names them. */
        private val IN_PROGRESS =
            listOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")

        /** Why is the state directory's own, from [StateDirectory]: never a path below `.ps`, never content. */
        private const val STATE_REFUSED = "git {} refused in {}: {}."

        private const val CREDENTIAL_FOUND =
            "git {} refused in {}: what it would send carries the push token stored in .ps/git-credentials. " +
                "Nothing has left this machine. Take the token out of those files or commits, and rotate it."

        private const val CREDENTIAL_UNREADABLE =
            "git {} refused in {}: .ps/git-credentials is not a regular file, or cannot be read, so the push " +
                "token cannot be searched for. Replace it with a regular file, or remove it."

        private const val CREDENTIAL_UNSEARCHED =
            "git {} refused in {}: the search for the push token did not run to the end, and nothing goes " +
                "out unsearched."

        /** `git grep -q` exits 0 when something matched and 1 when nothing did; anything else is an error. */
        private const val MATCH = 0
        private const val NO_MATCH = 1

        /** Commits per `git grep`, which keeps every argument list far below any system's limit. */
        private const val REVISIONS_PER_SEARCH = 256

        /** `git check-ignore` exits 1 for a path no rule ignores; 0 is ignored, 128 is an error. */
        private const val NO_RULE_MATCHED = 1

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

/** One finished `git` invocation — its exit code, its answer on [stdout], and what it said on [stderr]. */
private data class GitResult(val code: Int, val stdout: String, val stderr: String) {
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
