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
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 * **What it commits and pushes is kept from carrying the push token (#360).** The token lives in
 * `.ps/`, inside the repository, and a clone or a pull can deliver what switches a single guard
 * off — a `.gitignore` git will not read, a link where a file was, a name the filesystem folds to
 * `.ps`, a file git tracks inside it. So the guards are layered, each covering what the one before
 * cannot:
 *
 * 1. Reconciliation leaves `.ps` — the entry and what is under it — out by pathspec, in any ASCII
 *    case, whatever `.gitignore` says ([RECONCILE_SCOPE]).
 * 2. No commit, reconciliation or push runs unless `.ps` is the real state directory and git tracks
 *    nothing that is it or under it, under any name the filesystem folds to it ([StateDirectory]).
 * 3. The credential is replaced, never written through a link ([GithubRemote]), and git is only
 *    pointed at it while it is a regular file ([PushCredential]).
 * 4. The content gate: the working tree within a commit's scope before staging, what is staged
 *    before the commit, and every blob a push would send before the push, are searched for the
 *    stored token and for anything shaped like a GitHub token. A push names its one branch and its
 *    remote, and runs with replace refs off, so the search reads the objects the push sends — each
 *    once, for the commits that remote's tracking refs lack, which a stale ref understates
 *    ([OutgoingObjectScan], #373) — and HEAD's own tree at every push, whatever the refs say.
 *
 * What none of this reads: commit and tag messages, and content a filter keeps outside the blob.
 * And each check is of a path at one moment, not of a handle held to the write — a swap in between
 * is a window the checks do not close. Every refusal is one WARN that names why and never the token,
 * and a false — fail closed.
 */
class CommandLineGitSync(
    private val root: Path,
    environment: Map<String, String> = System.getenv(),
    /** Whose date decides when a directory git cannot open is said again (#372). */
    private val clock: Clock = Clock.systemDefaultZone(),
    private val waitFor: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : GitSync {
    /**
     * Carried on our own invocations rather than written into the repository's config, so the
     * user's working copy is left as they keep it (#267). See [PushCredential].
     */
    private val credential = PushCredential(root)

    private val process = GitProcess(root, environment)

    /** What a push would send, searched object by object, through the same calls as everything else here. */
    private val outgoing = OutgoingObjectScan(ProcessCalls(process, ::commandFor))

    private val stateDirectory = StateDirectory(root, TrackedStateEntries(root, environment))

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

    /**
     * Each directory git said it could not open, with the date it was last said (#372). Bounded by the
     * records repository itself: one entry per directory git has named, a handful per problem at most. An
     * entry outlives its directory opening again, so one that comes and goes is still said once a day.
     */
    private val unreadableSaidOn = ConcurrentHashMap<String, LocalDate>()

    /** The last head a push searched clean, and what it was searched against. */
    private val lastSearchedClean = AtomicReference<SearchedHead?>()

    override fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean =
        inRepository("commit") { commitScoped(record, paths) }

    override fun reconcile(): Boolean = inRepository("reconcile") { commitEverything() }

    override fun push(): Boolean = inRepository("push") { pushed() }

    // `git remote` lists names and prints nothing when there is none, so an empty answer is the
    // whole signal. A failure to run it answers false: unknown is not "configured". So does one that
    // could not start, which threw — with the records directory gone, out of the backup's check (the
    // review of #399) — and is said, as every other git call here that could not run is.
    override fun hasRemote(): Boolean = runCatching { remotesListed() }.getOrElse { remoteUnknown(it) }

    private fun remotesListed(): Boolean = git(listOf("remote")).let { it.succeeded() && it.stdout.isNotBlank() }

    private fun remoteUnknown(cause: Throwable): Boolean {
        logger.warn(REMOTE_UNKNOWN, root, cause.javaClass.simpleName)
        return false
    }

    override fun hasPushCredential(): Boolean = credential.isStored()

    private fun inRepository(what: String, action: () -> Boolean): Boolean {
        if (!isRepository) return false
        return neverThrowing(what) { inVerifiedState(what) && action() }
    }

    /**
     * Commits and pushes run only while `.ps` is the tracker's own state directory and git tracks
     * nothing that is it or under it, in any name that comes to it (#360). A link a pull swapped in, a
     * name the filesystem folds to `.ps`, or a file or link git tracks inside it turns state the server
     * writes into a path a commit can carry — raw frames, timers, the credential. Checked on every call,
     * because a pull can change it while the server runs.
     */
    private fun inVerifiedState(what: String): Boolean = when (val inspection = stateDirectory.forGit()) {
        is StateDirectory.Usable -> true
        is StateDirectory.Refused -> refusedState(what, inspection.refusal.reason)
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
        if (operationInProgress()) return waitingItOut()
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
    // picks them up. Said once, because the backup asks every minute while it is due. A submit waits
    // too: its staging marked a conflicted record resolved, markers and all (the review's N11).
    private fun waitingItOut(): Boolean {
        if (!waitingSaid.getAndSet(true)) logger.warn(OPERATION_IN_PROGRESS, root)
        return false
    }

    /**
     * One branch to one remote, named on the command line (#360). A bare `git push` sent whatever
     * `remote.<r>.push` or `push.default=matching` said — branches the search never looked at — so the
     * push names its refspec, `HEAD:refs/heads/<branch>`, and the remote git would choose for it. The
     * branch goes to its own name there: `branch.<b>.merge` is not consulted, so an upstream of another
     * name under `push.default=upstream` is not where this push goes. The search covers the commits that
     * remote's own branches lack.
     *
     * Before the first commit there is nothing to push, and that is answered as a push that succeeded
     * (#372 is the daily backup recording it as one). A remote with no URL — records kept without one is
     * a documented way to run — is nowhere to push, so nothing is searched for it, and with no remote at
     * all nothing is said either (#390).
     */
    private fun pushed(): Boolean {
        val head = headCommit() ?: return true
        val branch = currentBranch() ?: return detached()
        val remote = pushRemoteOf(branch)
        if (!hasDestination(remote)) return noRemote(remote)
        if (!searchedClean(SearchedHead(head, remote, fingerprintOfStore()))) return false
        val result = git(listOf("push", remote, "HEAD:refs/heads/$branch"))
        return result.succeeded() || failed("push", result)
    }

    private fun headCommit(): String? =
        git(listOf("rev-parse", "--verify", "--quiet", "HEAD")).takeIf { it.succeeded() }?.stdout?.trim()

    private fun hasDestination(remote: String): Boolean =
        listOf("remote.$remote.url", "remote.$remote.pushurl").any { configured(it) != null }

    // No remote at all is the remote-less way to run, said once at INFO by the boot report and the backup
    // schedule; said here, it was a warning at every push, every minute the backup was due (#390). A
    // remote that exists while the push goes to another name, which has no URL, is a fault, and is said.
    private fun noRemote(remote: String): Boolean {
        if (hasRemote()) logger.warn(NO_REMOTE, root, remote)
        return false
    }

    /**
     * A head already searched clean — for the same remote, against the same stored token — is not
     * searched again. A push that kept failing, to a remote that was down, searched the same commits at
     * every attempt: 23.4 s for 1,660 commits, every minute the backup was due (the review of ea1357c).
     */
    private fun searchedClean(head: SearchedHead): Boolean {
        if (lastSearchedClean.get() == head) return true
        if (!carriesNoToken("push") { outgoing.outcome(searchedForPush(head.remote), it) }) return false
        lastSearchedClean.set(head)
        return true
    }

    /**
     * What a push to [remote] is searched for, as `rev-list` argument lists: what it would send, and HEAD's own
     * tree, whatever the remote-tracking refs say. The tracker never fetches, so a ref stays where the last
     * push left it: after `remote set-url`, or with the remote re-created empty, it still names commits the
     * remote does not hold, and the range leaves out what they reach. A token still in HEAD's tree went out
     * unsearched that way (the review of 315f44e). HEAD's tree is what the search before #373 read of HEAD; a
     * commit between HEAD and a stale ref was not read then either, and is #376's.
     */
    private fun searchedForPush(remote: String): List<List<String>> = listOf(outgoingRange(remote), HEAD_TREE)

    /**
     * What a push to [remote] would send, as `rev-list` arguments: the commits none of its remote-tracking
     * branches holds, and what they carry. A push updates the branch it pushed, so after the first one this
     * is what was committed since; with none at all it is every commit. Another remote's branches do not
     * count — the review's N4: a commit already pushed to a second remote was skipped, and sent here
     * unsearched. The tracker configures no upstream (`push.default=current`), so `@{u}..HEAD` would name
     * nothing. The one place the range is decided, so a change to what counts as sent is made here.
     */
    private fun outgoingRange(remote: String): List<String> = listOf("HEAD", "--not", "--remotes=$remote")

    // What was stored when a head was searched, kept as a digest rather than as the secret itself.
    private fun fingerprintOfStore(): String = when (val stored = credential.stored()) {
        is Patterns -> MessageDigest.getInstance("SHA-256").digest(stored.asInput().toByteArray()).toHexString()
        else -> stored.toString()
    }

    private fun currentBranch(): String? =
        git(listOf("symbolic-ref", "--quiet", "--short", "HEAD")).takeIf { it.succeeded() }?.stdout?.trim()

    private fun detached(): Boolean {
        logger.warn("git push skipped in {}: HEAD is detached, so there is no branch to push", root)
        return false
    }

    // The remote git itself would push the branch to, in git's own order of precedence.
    private fun pushRemoteOf(branch: String): String =
        listOf("branch.$branch.pushRemote", "remote.pushDefault", "branch.$branch.remote")
            .firstNotNullOfOrNull { configured(it) } ?: DEFAULT_REMOTE

    private fun configured(key: String): String? =
        git(listOf("config", "--get", key)).takeIf { it.succeeded() }?.stdout?.trim()?.ifEmpty { null }

    /**
     * Searches the working tree within [scope], stages it, searches what is staged, and commits exactly
     * [scope]. `add` with a pathspec stages removals as well (git 2.0 and later), with or without `--all`.
     *
     * Searched before staging as well as after: a refusal found only after `add` left the file that
     * carries the token staged, where the next plain `git commit` takes it (the review's M5). Searched
     * with `--untracked`, which leaves out what git ignores, and the scope leaves `.ps` out besides.
     *
     * `git commit -- <paths>` is a partial commit: it takes those paths from the working tree and
     * ignores the rest of the index, which is what keeps another process's staged file out. On a
     * branch with no commit yet it is still a root commit (measured on git 2.48.1). It reads the
     * working tree again, so a file that changes between the search and the commit is committed
     * unsearched — which is why the push searches again, what was actually committed.
     */
    private fun committed(what: String, scope: List<String>, message: String): Boolean {
        if (!carriesNoToken(what) { grepped(it, listOf("--untracked", "--") + scope) }) return false
        if (!retryingOnContention(what) { git(listOf("add", "--all", "--") + scope) }) return false
        if (!carriesNoToken(what) { grepped(it, listOf("--cached", "--") + scope) }) return false
        return retryingOnContention(what) { git(listOf("commit", "--message", message, "--") + scope) }
    }

    /**
     * The content gate (#360): true when [search] finds no GitHub token in what a commit or a push would
     * carry. Two kinds of pattern are searched for: anything shaped like a GitHub token, always, so the
     * gate does not depend on what is stored and finds a token rotated out of the store (the review's R1);
     * and what the store holds, never in argv. It searches the content git would carry, not a path, so it
     * holds wherever a token turns up — though not in a commit or tag message, which it does not read. A
     * commit's side is searched by `git grep` ([grepped]), a push's by [OutgoingObjectScan] (#373).
     *
     * Fails closed, with one WARN that never carries what was searched for, or where it was found: a store
     * that is there and cannot be read, a search that did not read everything, a match.
     */
    private fun carriesNoToken(what: String, search: (StoredCredential) -> SearchOutcome): Boolean {
        val stored = credential.stored()
        if (stored == StoredCredential.Unreadable) return refused(CREDENTIAL_UNREADABLE, what)
        return when (search(stored)) {
            SearchOutcome.CLEAN -> true
            SearchOutcome.FOUND -> refused(CREDENTIAL_FOUND, what)
            SearchOutcome.UNSEARCHED -> refused(CREDENTIAL_UNSEARCHED, what)
        }
    }

    // One `git grep` search of a commit's side, its arguments [search]: a match, a clean end, or neither.
    private fun grepped(stored: StoredCredential, search: List<String>): SearchOutcome {
        val outcome = greps(stored, search).firstOrNull { it != NO_MATCH } ?: return SearchOutcome.CLEAN
        if (outcome == MATCH) return SearchOutcome.FOUND
        return SearchOutcome.UNSEARCHED
    }

    // The token shapes first, then what is stored — the second only runs if the first found nothing. The
    // stored values go on stdin as fixed strings, never in argv.
    private fun greps(stored: StoredCredential, search: List<String>): Sequence<Int> = sequence {
        yield(outcomeOf(git(listOf("grep", "-q", "-E") + TokenPatterns.SHAPES.flatMap { listOf("-e", it) } + search)))
        if (stored is Patterns) yield(outcomeOf(git(listOf("grep", "-q", "-F", "-f", "-") + search, stored.asInput())))
    }

    /**
     * What a grep's ending means for the gate. One that could not read a blob says so on stderr and
     * exits 1, the code for "nothing found" (measured on 2.48.1 and 2.53.0) — not a search that ran to
     * the end. A warning, such as a `.gitignore` git cannot follow, is not an error.
     */
    private fun outcomeOf(result: GitResult): Int {
        if (result.code == NO_MATCH && result.stderr.lines().any { it.startsWith("error:") }) return UNREAD
        return result.code
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
        sayUnreadable(status.stderr)
        return !status.succeeded() || status.stdout.isNotBlank()
    }

    /**
     * A directory git cannot open is reported on stderr alone: status exits 0 and lists nothing under it
     * (`warning: could not open directory 'problems/120804/': Permission denied`, git 2.48.1). Read from
     * stdout, what it holds was left out of every commit without a word (#372, the review's F8 of #360).
     * Each is said by the path git names, and nothing under it is read. A warning and not a failure: the
     * rest is committed. Read as a change, as before #360, it made every reconciliation an empty commit
     * that failed — which would now hold the daily backup at every check.
     *
     * Said once a day while it lasts, by the date on [clock], not once per process: reconciliation answers
     * true around it, so every day's backup is recorded without what it holds, and a server that runs for
     * weeks said it on the first day only (the review of #389). Once a day, so every day a backup is
     * recorded without it has said so — without this adapter knowing the schedule.
     *
     * Git's other warning that names a path it cannot read, `unable to access '<path>'`, is about a file
     * git reads for itself: the `.gitignore` of a directory it can list but not enter, or a linked one
     * (both measured), whose rules then do not apply. It leaves no record out, and the root `.gitignore`
     * has its own warning.
     */
    private fun sayUnreadable(stderr: String) {
        stderr.lines().mapNotNull { UNREADABLE_DIRECTORY.matchEntire(it.trimEnd()) }.forEach(::sayOnceToday)
    }

    // `put` answers the date it replaced, so the same date means this directory was said today already.
    private fun sayOnceToday(line: MatchResult) {
        val directory = line.groups["directory"]?.value ?: return
        val today = LocalDate.now(clock)
        if (unreadableSaidOn.put(directory, today) == today) return
        logger.warn(UNREADABLE, directory, root, line.groups["reason"]?.value)
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

    /** One `git` invocation, as [GitProcess] runs every one of them. */
    private fun git(args: List<String>, input: String? = null): GitResult = process.run(commandFor(args), input)

    /**
     * The exact argument list handed to the process, credential prefix included. Internal
     * because it is the wiring itself: [PushCredential] computing the right `-c` is worth
     * nothing if it never reaches a git call, and that is not observable from the outside.
     */
    internal fun commandFor(args: List<String>): List<String> = listOf(GIT) + credential.gitConfig() + args

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
         * to `.ps`, is not matched; git tracking anything under it refuses before git runs, and the
         * content gate stands behind that.
         *
         * **The entry itself as well as what is under it.** A partial commit takes every path the
         * pathspec matches in HEAD too: with a tracked `.ps` link taken out of the index by hand and a
         * real directory in its place, it found a directory where a link was tracked and stopped —
         * "'.ps' does not have a commit checked out" (measured on 2.48.1 and 2.53.0).
         */
        private val RECONCILE_SCOPE = "[${StateDirectory.NAME.first()}]${StateDirectory.NAME.drop(1)}".let { glob ->
            listOf(".", ":(exclude,glob,icase)$glob", ":(exclude,glob,icase)$glob/**")
        }

        /** Said once per process, so it stays readable instead of drowning every other line. */
        const val NOT_A_REPOSITORY =
            "{} is not a git repository, so records are written but never committed. " +
                "Run `git init` there and restart to keep a history — this is said only once."

        private const val STATE_NOT_IGNORED_WARNING =
            "git does not ignore .ps/ in {}: its .gitignore lacks the rule, or git cannot read the " +
                "file — git never follows a .gitignore that is a symbolic link, and then none of its " +
                "rules (.DS_Store, editor state) apply. The tracker's own commits still leave .ps/ out, " +
                "and it searches what it commits and pushes for the token; another tool's git add does " +
                "neither. Make .gitignore a regular file that holds the rule. This is said only once."

        /** The tracker's state directory, spelled with its slash so git knows it is a directory. */
        private const val STATE_DIRECTORY = "${StateDirectory.NAME}/"

        private const val NO_REMOTE =
            "git push skipped in {}: no remote named {} has a URL, so there is nowhere to push and nothing was searched."

        private const val REMOTE_UNKNOWN =
            "git remote could not run in {} ({}), so whether it has a remote is unknown, and it is answered as none."

        /**
         * Git's own line for a directory it could not open, as it says it in the C locale ([GitProcess]).
         * The path is printed raw inside git's quotes, quotes of its own included (`'it's/'`, measured), so it
         * runs to the last `': `; the reason, an `strerror` text, holds no quote.
         */
        private val UNREADABLE_DIRECTORY =
            Regex("""warning: could not open directory '(?<directory>.+)': (?<reason>[^']*)""")

        private const val UNREADABLE =
            "git cannot open {} in {} ({}), so no new file or change under it is committed or pushed until it " +
                "can, while what was committed before still goes up: make it readable to the user the tracker " +
                "runs as. This is said once a day while it lasts."

        private const val OPERATION_IN_PROGRESS =
            "{} has a merge, cherry-pick, revert or rebase in progress, so the tracker's commits wait " +
                "rather than commit inside it: records stay uncommitted until it is finished or aborted. " +
                "This is said only once."

        /** Where git marks an operation the user has open, as `git rev-parse --git-path` names them. */
        private val IN_PROGRESS =
            listOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")

        /** Why is the state directory's own, from [StateDirectory]: never a path below `.ps`, never content. */
        private const val STATE_REFUSED = "git {} refused in {}: {}."

        private const val CREDENTIAL_FOUND =
            "git {} refused in {}: what it would send carries a GitHub token — the one stored in " +
                ".ps/git-credentials, or one shaped like it. It was not sent; revoke the token on GitHub " +
                "and remove it from history."

        private const val CREDENTIAL_UNREADABLE =
            "git {} refused in {}: .ps/git-credentials is not a regular file, or cannot be read, so the push " +
                "token cannot be searched for. Replace it with a regular file, or remove it."

        private const val CREDENTIAL_UNSEARCHED =
            "git {} refused in {}: the search for the push token did not run to the end, and nothing goes " +
                "out unsearched."

        /** `git grep -q` exits 0 when something matched and 1 when nothing did; anything else is an error. */
        private const val MATCH = 0
        private const val NO_MATCH = 1

        /** A grep that exited as if nothing matched, after failing to read something it was asked to search. */
        private const val UNREAD = -2

        /** Where a push goes when git names no other remote for the branch. */
        private const val DEFAULT_REMOTE = "origin"

        /** HEAD's own tree, and everything under it, as `rev-list --objects` takes it. */
        private val HEAD_TREE = listOf("HEAD^{tree}")

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

        private const val GIT = "git"

        private val logger = LoggerFactory.getLogger(CommandLineGitSync::class.java)
    }
}

/** A head searched clean: the commit, the remote it was searched for, and a digest of what was stored. */
private data class SearchedHead(val commit: String, val remote: String, val store: String)
