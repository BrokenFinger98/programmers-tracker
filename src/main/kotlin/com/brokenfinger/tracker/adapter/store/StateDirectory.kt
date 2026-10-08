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
 * still leads elsewhere.
 *
 * **Its contents are inspected too ([inspected]).** A pull can deliver a tracked file or a link
 * *inside* the real directory — a credential store holding one letter, `.ps/raw` leading into the
 * tree — and git runs, and the credential is stored, only while nothing below `.ps` is a link and git
 * tracks nothing there. What git tracks is git's to say: [TrackedState] is answered by the git adapter
 * and handed in by the composition root, so this package runs no git.
 *
 * **Every writer of state asks it too ([forWriting])** — raw frames, timers, the backup marker, the
 * seed ledger. While `.ps` is not the real directory, or holds a link or a tracked file, each skips its
 * write and says so once, rather than land state where a commit can carry it. The credential and git
 * itself ask [inspected].
 */
class StateDirectory(
    private val recordRoot: Path,
    private val tracked: TrackedState = TrackedState.UNASKED,
    private val listing: (Path) -> Set<String> = ::namesOnDisk,
) {
    /** The state directory, created if absent, or null when what answers to [NAME] is not it. Never throws. */
    fun verified(): Path? = runCatching { verify(recordRoot.resolve(NAME)) }.getOrNull()

    /**
     * Whether git may run over the record repository and the credential be stored there: [verified],
     * with no link anywhere below it, and nothing below it tracked by git. Never throws; a refusal
     * carries a reason a WARN can say, which names no path below `.ps` and no content.
     */
    fun inspected(): Inspection = inspect { Refused(UNANSWERED) }

    /**
     * For a writer of state: [inspected], except that git which cannot say what it tracks does not
     * stop the write. Captures, timers and the ledger must not depend on git working; commits and
     * pushes refuse in that case instead.
     */
    fun forWriting(): Inspection = inspect { directory -> Usable(directory) }

    private fun inspect(unanswered: (Path) -> Inspection): Inspection {
        val directory = verified() ?: return Refused(NOT_THE_DIRECTORY)
        val linked = runCatching { holdsALink(directory) }.getOrNull() ?: return Refused(NOT_INSPECTED)
        if (linked) return Refused(HOLDS_A_LINK)
        return trackedOrNot(directory, unanswered)
    }

    private fun trackedOrNot(directory: Path, unanswered: (Path) -> Inspection): Inspection = when (tracked.any()) {
        false -> Usable(directory)
        true -> Refused(TRACKED)
        null -> unanswered(directory)
    }

    // Walked without following a link, so a link is seen as one and never entered.
    private fun holdsALink(directory: Path): Boolean =
        Files.walk(directory).use { paths -> paths.anyMatch { Files.isSymbolicLink(it) } }

    private fun verify(directory: Path): Path? {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectories(directory)
        if (!listedByItsOwnName()) return null
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return null
        if (directory.toRealPath() != recordRoot.toRealPath().resolve(NAME)) return null
        return directory
    }

    // The root's own listing, as stored on disk. Handed in so a test can pin this check on a
    // filesystem that cannot fold names, where nothing else would show it at work (#360).
    private fun listedByItsOwnName(): Boolean = NAME in listing(recordRoot)

    sealed interface Inspection

    /** Git may run, and the credential be stored, in [directory]. */
    data class Usable(val directory: Path) : Inspection

    /** Neither may, because of [reason]. */
    data class Refused(val reason: String) : Inspection

    companion object {
        const val NAME = ".ps"

        /** [NAME] as a glob with no literal text before its first wildcard, for git pathspecs. */
        val GLOB = "[${NAME.first()}]${NAME.drop(1)}"

        private const val UNTRACK = "if git tracks it, run `git rm -r --cached .ps` and commit that"

        const val NOT_THE_DIRECTORY =
            "what answers to .ps is not the tracker's own state directory — a link, or a name the filesystem " +
                "folds to .ps, such as .PS. Replace it with a real directory named exactly .ps; $UNTRACK"

        const val HOLDS_A_LINK =
            ".ps holds a symbolic link, and whatever is written through it lands where it leads. Remove the " +
                "link; $UNTRACK"

        const val TRACKED =
            "git tracks files under .ps, so whatever the server writes there is a change any `commit -a` " +
                "publishes, the push credential included. Run `git rm -r --cached .ps` and commit that"

        const val UNANSWERED = "git could not say whether it tracks anything under .ps"

        const val NOT_INSPECTED = "what lies under .ps could not be read through"
    }
}

private fun namesOnDisk(directory: Path): Set<String> =
    Files.newDirectoryStream(directory).use { entries -> entries.map { it.fileName.toString() }.toSet() }

/**
 * Whether git tracks anything under the state directory — a question for git, which this package
 * does not run. The git adapter answers it and the composition root hands it in (#360).
 */
fun interface TrackedState {
    /** True when git tracks an entry under `.ps`, in any case; false when none; null when git cannot say. */
    fun any(): Boolean?

    companion object {
        /** Nobody to ask: the filesystem decides alone. */
        val UNASKED = TrackedState { false }
    }
}
