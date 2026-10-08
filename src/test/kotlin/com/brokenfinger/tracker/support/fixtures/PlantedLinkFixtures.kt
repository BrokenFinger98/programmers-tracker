package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.git.PushCredential
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// Object mother for a planted link (dev rules §6.4, #354). Git stores symbolic links, so a records
// repository can arrive by clone or pull with a link under problems/ aimed at what its root also holds —
// the push token first of all. Every reader that must refuse one is tested against the same planting,
// so "refused" means the same thing in each of them.

/** The credential inside [A_PUSH_TOKEN_LINE] — what must never appear in an answer, a page or a log line. */
const val A_PUSH_CREDENTIAL = "not-a-real-token"

/** A stand-in for the push token, shaped like the line the real file holds and never a real one (dev rules §7.3). */
const val A_PUSH_TOKEN_LINE = "https://x-access-token:$A_PUSH_CREDENTIAL@github.com"

/** The push token where the records repository really keeps it — `.ps/git-credentials`, beside `problems/`. */
fun aPushTokenIn(root: Path): Path {
    val file = root.resolve(PushCredential.FILE)
    Files.createDirectories(file.parent)
    return Files.writeString(file, "$A_PUSH_TOKEN_LINE\n")
}

/** [link] made a symbolic link to [target], stored relative as git stores one. Its parents are created. */
fun aLink(link: Path, target: Path): Path {
    Files.createDirectories(link.parent)
    return Files.createSymbolicLink(link, link.parent.relativize(target))
}

/** Whether links and FIFOs can be made under [root] at all — a POSIX filesystem, which a Windows runner's is not. */
fun canPlantLinksIn(root: Path): Boolean = root.fileSystem.supportedFileAttributeViews().contains("posix")

/** [path] made a FIFO, which only `mkfifo` makes; false where there is none to run. Its parent must exist. */
fun madeFifo(path: Path): Boolean =
    runCatching { ProcessBuilder("mkfifo", path.toString()).start().waitFor() == 0 }.getOrDefault(false)

/** `.p` and U+017F, LATIN SMALL LETTER LONG S, which case-folds to `s`: APFS answers `.ps` with it (#360). */
const val A_LONG_S_STATE_DIRECTORY = ".pſ"

/**
 * Whether this filesystem answers [name] with an entry created as [alias] — `.PS` for `.ps` on a
 * case-insensitive volume, [A_LONG_S_STATE_DIRECTORY] where Unicode case folding applies as well, as
 * on APFS. A clone can deliver either, so a test that needs one probes for it under [root] first.
 */
fun foldsTogether(root: Path, alias: String, name: String): Boolean = runCatching {
    val probe = Files.createTempDirectory(root, "fold")
    val created = Files.createDirectory(probe.resolve(alias))
    val folds = Files.exists(probe.resolve(name), LinkOption.NOFOLLOW_LINKS)
    Files.delete(created)
    Files.delete(probe)
    folds
}.getOrDefault(false)
