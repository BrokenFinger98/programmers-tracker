package com.brokenfinger.tracker.support.fixtures

import java.nio.file.Files
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
    val file = root.resolve(".ps/git-credentials")
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
