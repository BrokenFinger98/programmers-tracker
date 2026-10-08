package com.brokenfinger.tracker.adapter.store

import java.nio.file.FileAlreadyExistsException
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
 * still leads elsewhere.
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
 * needs besides is that its own path follows no link, which [pathFor] checks before every write and a
 * rename or a no-follow open keeps for the file itself.
 *
 * Git and the credential ask [forGit]; every writer of state asks [forWriting]. A refusal carries a
 * [Refusal] — structural, until someone changes the repository, or transient, worth asking again.
 */
class StateDirectory(
    private val recordRoot: Path,
    private val tracked: TrackedState,
    private val listing: (Path) -> Set<String> = ::namesOnDisk,
) {
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
     * included — for a writer whose file is not directly in `.ps`. A stat per segment, so it runs before
     * every write: a pull can swap a directory for a link between two frames. Never throws.
     */
    fun pathFor(vararg segments: String): Inspection = runCatching { realDirectories(segments.toList()) }
        .getOrElse { Refused(Refusal.NOT_INSPECTED) }

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

    private fun verified(directory: Path): Path? {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectories(directory)
        if (NAME !in listing(recordRoot)) return null
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return null
        if (directory.toRealPath() != recordRoot.toRealPath().resolve(NAME)) return null
        return directory
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

    private fun createdOrThere(directory: Path) {
        runCatching { Files.createDirectory(directory) }.onFailure { if (it !is FileAlreadyExistsException) throw it }
    }

    sealed interface Inspection

    /** Usable: git may run, the credential be stored, the state be written in [directory]. */
    data class Usable(val directory: Path) : Inspection

    /** Not, because of [refusal]. */
    data class Refused(val refusal: Refusal) : Inspection

    /**
     * Why `.ps` was refused, and whether that can change on its own. A [transient] refusal is asked
     * again at the next write; a structural one stands until someone changes the repository. Each
     * [reason] names no path below `.ps` and no content, so a WARN can say it.
     */
    enum class Refusal(val transient: Boolean, val reason: String) {
        NOT_THE_DIRECTORY(
            transient = false,
            reason = "what answers to .ps is not the tracker's own state directory — a link, or a name the " +
                "filesystem folds to .ps, such as .PS. Replace it with a real directory named exactly .ps; " +
                UNTRACK,
        ),
        TRACKED(
            transient = false,
            reason = "git tracks files under .ps, so whatever the server writes there is a change any " +
                "`commit -a` publishes, the push credential included. Run `git rm -r --cached .ps` and commit that",
        ),
        HOLDS_A_LINK(
            transient = false,
            reason = "a directory the server writes into under .ps is a symbolic link, and whatever is written " +
                "through it lands where it leads. Remove the link; $UNTRACK",
        ),
        UNANSWERED(transient = true, reason = "git could not say whether it tracks anything under .ps"),
        NOT_INSPECTED(transient = true, reason = "what answers to .ps could not be read"),
    }

    companion object {
        const val NAME = ".ps"
    }
}

private const val UNTRACK = "if git tracks it, run `git rm -r --cached .ps` and commit that"

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
}
