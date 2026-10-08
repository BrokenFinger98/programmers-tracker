package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.git.PushCredential
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// Object mother for a planted link (dev rules §6.4, #354). Git stores symbolic links, so a records
// repository can arrive by clone or pull with a link under problems/ aimed at what its root also holds —
// the push token first of all. Every reader that must refuse one is tested against the same planting,
// so "refused" means the same thing in each of them — and every writer too (#361).

/** The credential inside [A_PUSH_TOKEN_LINE] — what must never appear in an answer, a page or a log line. */
const val A_PUSH_CREDENTIAL = "not-a-real-token"

/** A stand-in for the push token, shaped like the line the real file holds and never a real one (dev rules §7.3). */
const val A_PUSH_TOKEN_LINE = "https://x-access-token:$A_PUSH_CREDENTIAL@github.com"

/**
 * A string shaped like a classic GitHub token — prefix, underscore, 36 characters — and never a real
 * one, built here rather than written out so no token-shaped literal is ever committed (#360).
 */
fun aGithubShapedToken(fill: Char = 'A'): String = "ghp" + "_" + fill.toString().repeat(36)

/** The same for a fine-grained token: `github_pat_` and 82 characters. */
fun aFineGrainedShapedToken(fill: Char = 'B'): String = "github" + "_pat_" + fill.toString().repeat(82)

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

/** What a file outside the records holds before a writer meets a link to it, and must still hold after (#361). */
const val NOT_OURS = "not ours\n"

/** A file outside the records repository, holding [NOT_OURS] — where a planted link would lead a writer. */
fun aFileNotOurs(directory: Path, name: String = "not-ours.md"): Path =
    Files.writeString(directory.resolve(name), NOT_OURS)

/** The names directly in [directory], sorted: empty when nothing was created there. */
fun namesIn(directory: Path): List<String> =
    Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

/** Whether links and FIFOs can be made under [root] at all — a POSIX filesystem, which a Windows runner's is not. */
fun canPlantLinksIn(root: Path): Boolean = root.fileSystem.supportedFileAttributeViews().contains("posix")

/**
 * Whether [root] keeps POSIX permissions, for a test that sets or reads an owner-only mode. The same
 * probe as [canPlantLinksIn] today, named for what such a test needs rather than borrowed from links.
 */
fun keepsPosixPermissions(root: Path): Boolean = root.fileSystem.supportedFileAttributeViews().contains("posix")

/**
 * [directory] with every permission taken away, as `chmod 000` leaves it, while [action] runs — and given
 * back afterwards, so a `@TempDir` can still be cleaned up. A superuser opens it anyway: a test that needs
 * it unreadable assumes `!Files.isReadable(directory)` inside [action], and skips under root.
 */
fun <T> sealedWhile(directory: Path, action: () -> T): T {
    Files.setPosixFilePermissions(directory, emptySet())
    try {
        return action()
    } finally {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    }
}

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
