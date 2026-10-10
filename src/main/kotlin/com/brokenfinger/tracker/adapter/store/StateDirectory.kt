package com.brokenfinger.tracker.adapter.store

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * The record repository's state directory, `.ps` (design §5.1), and whether what answers to that name
 * is it (#360).
 *
 * The push credential, raw frames, timers and the backup marker all live there, and the directory is
 * kept out of every commit by its name. Git stores links and a filesystem may fold names, so a clone
 * or a pull can put something else in its place, and whatever is written into it is then a path git
 * tracks:
 *
 * - **a tracked link into the tree.** Git treats an ignored file as expendable, so a pull that
 *   carries a link named `.ps` deletes the real directory to check it out.
 * - **a name the filesystem folds to `.ps`.** A case-insensitive volume answers `.ps` with `.PS`,
 *   and APFS answers it with `.pſ` too (U+017F folds to `s`). Git sees the name on disk, which the
 *   ignore rule and the pathspec do not name.
 *
 * So the directory is verified rather than assumed. Absent, it is created as a real directory, with the
 * record repository above it if that is not there yet, as every writer of state always did.
 * Present, it must be listed by exactly that name, be a directory without following a link, and have
 * the real root with `.ps` appended as its real path. The listing is what catches a folded alias
 * everywhere: `readdir` returns the name on disk, while `toRealPath` returns the name as asked for on
 * Linux over a case-insensitive mount — measured in the tracker's own image on a macOS bind mount,
 * where the real path of an alias came back as `.ps`. The real path catches what is a directory and
 * still leads elsewhere. These are the checks every directory a record write walks gets, so `.ps` takes
 * that same step, [RecordBound]'s (#386).
 *
 * **And git must track nothing that is it or under it** — a pull can deliver a tracked file inside the
 * real directory, a credential store holding one letter, a link at `.ps/raw`, under any name that comes
 * to `.ps`. What git tracks is git's to say: [TrackedState] is answered by the git adapter and handed in
 * by the composition root, so this package runs no git.
 *
 * **Nothing walks the directory.** A walk looked for a link anywhere below `.ps`, read every entry while
 * the tracker's own writers replaced theirs, and took an entry that vanished mid-walk for a refusal: 444
 * of 3,000 inspections of a healthy directory, and whole sessions of raw frames (the review of ea1357c).
 * No writer needs that answer. A link that arrives by pull is tracked, and refused as such; what a writer
 * needs besides is that its own way follows no link, which [open] holds from before every write to it.
 *
 * **A writer holds what was checked** (#374). A check is of a path at one moment, and a write by path
 * resolved it again: `.ps` swapped for a link while git answered led the write wherever it pointed (N10 in
 * the review of #360; measured, the timers document and the push credential written into `problems/`).
 * So a writer is handed the directory itself, open — [openForWriting], [open] — through a
 * [DirectoryHandle], and writes in it by name: what `.ps` comes to name after the check is never followed.
 * Where the platform gives no handle, as Windows does not, [DirectoryHandles] holds it by path, as before.
 *
 * Git asks [forGit]; every writer of state asks [forWriting], or [openForWriting] to be handed `.ps`. A
 * refusal carries a [Refusal] — structural, until someone changes the repository, or transient, worth
 * asking again.
 */
class StateDirectory internal constructor(
    private val recordRoot: Path,
    private val tracked: TrackedState,
    listing: (Path) -> Set<String>,
    private val handles: DirectoryHandles,
) {
    /** The state directory of [recordRoot], held through this platform's handles where it gives them (#374). */
    constructor(recordRoot: Path, tracked: TrackedState, listing: (Path) -> Set<String> = ::namesOnDisk) :
        this(recordRoot, tracked, listing, DirectoryHandles.ON_THIS_PLATFORM)

    // `.ps` is checked by the step every record write takes (#386), with the root's listing handed in as before.
    private val bound = RecordBound.underRoot(recordRoot, setOf(NAME), DiskAnswers(namesIn = listing))

    /**
     * Whether git may commit and push over the record repository, and the credential be stored there:
     * `.ps` is the tracker's own directory and git tracks nothing that is it or under it. Git that
     * cannot say refuses too — unknown is not clean. Never throws.
     */
    fun forGit(): Inspection = inspect(whenUnanswered = Refused(Refusal.UNANSWERED))

    /**
     * For a writer of state: [forGit], except that git which cannot say what it tracks does not stop
     * the write. Captures, timers and the ledger must not depend on git working; commits and pushes
     * refuse in that case instead.
     */
    fun forWriting(): Inspection = inspect(whenUnanswered = null)

    /**
     * The directory [segments] name below `.ps`, each created if absent and none of them a link, `.ps`
     * included — for a reader of what a writer keeps there, such as the raw work list. A stat per segment:
     * a pull can swap a directory for a link at any time. A writer is handed the directory held, [open].
     * Never throws.
     */
    fun pathFor(vararg segments: String): Inspection = runCatching { realDirectories(segments.toList()) }
        .getOrElse { Refused(Refusal.NOT_INSPECTED) }

    /**
     * [forWriting], with `.ps` held (#374). It is opened first, through the record root and without following a
     * link, and handed over only if, once git has answered, `.ps` is still the directory held: a write through it
     * lands there, whatever `.ps` comes to name. Swapped meanwhile, it is [Refusal.CHANGED], and nothing is handed
     * over. The caller closes what it is handed. Never throws.
     */
    fun openForWriting(): Opening {
        val held = runCatching { stateDirectoryHeld(makingTheRoot = true) }.getOrNull()
        val inspection = forWriting()
        if (inspection is Refused) return inspection.also { held?.close() }
        return stillChecked(held)
    }

    /**
     * [pathFor], held (#374): the directory [segments] name below `.ps`, each made where absent and opened through
     * the one above it without following a link, `.ps` through the record root — for a writer whose file is not
     * directly in `.ps`, before every write. The caller closes what it is handed. Never throws.
     */
    fun open(vararg segments: String): Opening = runCatching { heldBelow(segments.toList()) }
        .getOrElse { Refused(Refusal.NOT_INSPECTED) }

    /**
     * Every path git has ever tracked below `.ps`, relative to it ([TrackedState.pathsEverTracked], #377): a
     * file that answers to one may be what a pull delivered. Unanswered, with git's reason, when it cannot say.
     * Never throws.
     */
    fun pathsEverTracked(): TrackedHistory = runCatching { tracked.pathsEverTracked() }
        .getOrElse { TrackedHistory.Unanswered(it.javaClass.simpleName) }

    private fun inspect(whenUnanswered: Inspection?): Inspection {
        val directory =
            runCatching { verified(recordRoot.resolve(NAME)) }.getOrElse { return Refused(Refusal.NOT_INSPECTED) }
                ?: return Refused(Refusal.NOT_THE_DIRECTORY)
        return when (tracked.tracksAnything()) {
            false -> Usable(directory)
            true -> Refused(Refusal.TRACKED)
            null -> whenUnanswered ?: Usable(directory)
        }
    }

    // Made where absent, a real directory, listed by the root under exactly its name, and resolving to the path walked.
    // The path handed back is the one configured, as callers were always given.
    private fun verified(directory: Path): Path? {
        try {
            bound.made(directory) ?: return null
        } catch (out: OutOfBounds) {
            return null
        }
        return directory
    }

    // Handed over only while `.ps`, looked at without following a link, is the directory held.
    private fun stillChecked(held: DirectoryHandle?): Opening {
        if (held != null && isStill(held)) return Opened(held)
        held?.close()
        return Refused(Refusal.CHANGED)
    }

    private fun isStill(held: DirectoryHandle): Boolean =
        runCatching { held.isAt(recordRoot.resolve(NAME)) }.getOrDefault(false)

    // Each directory opened through the one above it, which is let go once it has: nothing is resolved again.
    private fun heldBelow(segments: List<String>): Opening {
        val held = segments.fold(stateDirectoryHeld()) { parent, segment -> parent?.use { it.child(segment) } }
        return held?.let(::Opened) ?: Refused(Refusal.HOLDS_A_LINK)
    }

    // `.ps`, opened through the record root's own handle and never through a link: made where absent, and the
    // record root above it too where a writer of state asks, as [forWriting] makes it.
    private fun stateDirectoryHeld(makingTheRoot: Boolean = false): DirectoryHandle? {
        if (makingTheRoot) Files.createDirectories(recordRoot)
        return handles.open(recordRoot).use { it.child(NAME) }
    }

    // `all` stops at the first that is not a real directory, so nothing is created through a link.
    private fun realDirectories(segments: List<String>): Inspection {
        val directories = segments.runningFold(recordRoot.resolve(NAME)) { parent, segment -> parent.resolve(segment) }
        if (directories.all { isRealDirectory(it) }) return Usable(directories.last())
        return Refused(Refusal.HOLDS_A_LINK)
    }

    // Created when absent — by another writer meanwhile, too — and then a directory without following a link.
    private fun isRealDirectory(directory: Path): Boolean {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) createdOrThere(directory)
        return Files.isDirectory(directory, NOFOLLOW_LINKS)
    }

    sealed interface Inspection

    /** Usable: git may run, the credential be stored, the state be written in [directory]. */
    data class Usable(val directory: Path) : Inspection

    /** What a writer of state is handed (#374): the directory, [Opened], or why not, [Refused]. */
    sealed interface Opening

    /** Opened: written in through [handle], by name, and closed by whoever was handed it. */
    class Opened(val handle: DirectoryHandle) : Opening

    /** Not, because of [refusal]. */
    data class Refused(val refusal: Refusal) :
        Inspection,
        Opening

    /**
     * Why `.ps` was refused, and whether that can change on its own. A [transient] refusal is asked
     * again at the next write; a structural one stands until someone changes the repository. Each
     * [reason] names no path below `.ps` and no content, so a WARN can say it.
     */
    enum class Refusal(val transient: Boolean, val reason: String) {
        NOT_THE_DIRECTORY(
            transient = false,
            reason = "what answers to .ps is not the tracker's own state directory — a link, or a name the " +
                "filesystem folds to .ps, such as .PS. Replace it with a real directory named exactly .ps, moving " +
                "into it first what it holds; $UNTRACK",
        ),
        TRACKED(
            transient = false,
            reason = "git tracks files under .ps, in some spelling of the name, so whatever the server writes " +
                "there is a change any `commit -a` publishes, the push credential included. " +
                "`$LIST_EVERY_SPELLING` lists them. First, and without fail, delete from disk every one git put " +
                "there rather than this server: left behind untracked, one reads as the server's own, which tells " +
                "them apart only while git's history names it, and an expired reflog or a rewritten history stops " +
                "naming it. Then run `$UNTRACK_EVERY_SPELLING` and commit that",
        ),
        HOLDS_A_LINK(
            transient = false,
            reason = "a directory the server writes into under .ps is a symbolic link, and whatever is written " +
                "through it lands where it leads. Replace it with a real directory, moving into it first what lies " +
                "behind it, since the link removed alone takes the raw sessions there off the work list; $UNTRACK",
        ),
        UNANSWERED(transient = true, reason = "git could not say whether it tracks anything under .ps"),
        NOT_INSPECTED(transient = true, reason = "what answers to .ps could not be read"),
        CHANGED(
            transient = true,
            reason = "what answers to .ps changed while it was being checked — swapped for a link, as a pull swaps " +
                "it, most likely — so nothing was written there",
        ),
    }

    companion object {
        const val NAME = ".ps"
    }
}

// Every spelling a filesystem folds to `.ps`: `:(icase)` folds ASCII alone, so `.pſ` (U+017F) needs its own
// pathspec, and `.ps` alone named none of the others (the review of PR #395, measured on APFS). The tests run
// these commands as the owner reads them, for each spelling.
private const val LIST_EVERY_SPELLING = "git ls-files -- ':(icase).ps' ':(icase).pſ'"
private const val UNTRACK_EVERY_SPELLING = "git rm -r --cached --ignore-unmatch -- ':(icase).ps' ':(icase).pſ'"
private const val UNTRACK = "if git tracks anything there, run `$UNTRACK_EVERY_SPELLING` and commit that"

/** The names [directory] lists, as `readdir` returns them: the name on disk, whatever name it was reached by. */
internal fun namesOnDisk(directory: Path): Set<String> =
    Files.newDirectoryStream(directory).use { entries -> entries.map { it.fileName.toString() }.toSet() }

/**
 * Whether git tracks anything that is the state directory or lies under it — a question for git, which
 * this package does not run. The git adapter answers it and the composition root hands it in (#360).
 * There is no default: a writer that was never told cannot assume "nothing".
 */
fun interface TrackedState {
    /** True when git tracks such an entry; false when none; null when git cannot say. */
    fun tracksAnything(): Boolean?

    /**
     * Every path git has ever tracked below the state directory, relative to it and spelled as git stored it
     * (#377): what a pull may have delivered there, whether or not git still tracks it. Ever, as far as git
     * remembers: a reflog entry that expired, or a history rewritten, names a path no more. When git cannot say,
     * [TrackedHistory.Unanswered] carries its reason — and an answer never given is that, so unknown is never
     * "nothing".
     */
    fun pathsEverTracked(): TrackedHistory = TrackedHistory.Unanswered(NEVER_ASKED)
}

/** What git said of the paths it has ever tracked below `.ps` (#377). */
sealed interface TrackedHistory {
    /** Every such path, relative to `.ps`, spelled as git stored it. */
    data class Known(val paths: Set<String>) : TrackedHistory

    /** Git could not say. [reason] is its own first line, or how it ended — never a file's content. */
    data class Unanswered(val reason: String) : TrackedHistory
}

private const val NEVER_ASKED = "nothing answers what git has tracked here"
