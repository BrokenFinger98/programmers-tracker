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
 * So the directory is verified rather than assumed. Absent, it is created as a real directory.
 * Present, it must be listed by exactly that name, be a directory without following a link, and have
 * the real root with `.ps` appended as its real path. The listing is what catches a folded alias
 * everywhere: `readdir` returns the name on disk, while `toRealPath` returns the name as asked for on
 * Linux over a case-insensitive mount — measured in the tracker's own image on a macOS bind mount,
 * where the real path of an alias came back as `.ps`. The real path catches what is a directory and
 * still leads elsewhere.
 *
 * Only git and the credential consult this. Raw frames, timers, the backup marker and the seed
 * ledger are still written into whatever answers to `.ps`: a capture is never dropped over where it
 * lands, and nothing written there is committed or pushed by the tracker while git refuses.
 */
class StateDirectory(private val recordRoot: Path) {
    /** The state directory, created if absent, or null when what answers to [NAME] is not it. Never throws. */
    fun verified(): Path? = runCatching { verify(recordRoot.resolve(NAME)) }.getOrNull()

    private fun verify(directory: Path): Path? {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
        if (!listedByItsOwnName()) return null
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return null
        if (directory.toRealPath() != recordRoot.toRealPath().resolve(NAME)) return null
        return directory
    }

    private fun listedByItsOwnName(): Boolean =
        Files.newDirectoryStream(recordRoot).use { entries -> entries.any { it.fileName.toString() == NAME } }

    companion object {
        const val NAME = ".ps"
    }
}
