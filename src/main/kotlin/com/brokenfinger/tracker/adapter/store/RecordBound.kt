package com.brokenfinger.tracker.adapter.store

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer

/**
 * Where a file in the records repository may lie, and the walk that finds it (#361) — shared by [RecordWrites], which
 * writes through it, and [RecordReads], which reads what those writers keep at the root, so a reader and its writer
 * agree on what is in bounds (#387).
 *
 * **The bound.** The target must lie below the root as configured and name no `.` or `..` on the way — nobody is
 * handed one, and folding one away lexically let a root-level path climb back out — and it must fall under the bound:
 * inside `problems/` for a problem's files, and at the root's own level only the names kept there (`log`, `tags`, the
 * seeds, the heartbeat's marker), so never `.git` or `.ps`. Only the root is resolved, physically and from the path as
 * configured, as git and the lock resolve it, because it may sit behind a link by configuration (macOS's `/var`, a
 * `~/ps-records` link).
 *
 * **The walk.** Every directory from the real root down to the target's own is walked one name at a time. Each must be
 * a real directory, not a link; its parent must list it under exactly the name walked; and its real path must be the
 * path walked — both compared as text after NFC. So the walk never passes a link, and a name the filesystem folds, such
 * as a hand-made `Problems`, `PROBLEMS` or `problemſ` answering for `problems`, is refused before anything is made
 * inside it. Where that is caught differs (#361's review):
 * - on the host (APFS) and on Windows, by either check, since the real path answers the case on disk;
 * - in the tracker's image, a Linux container over a macOS bind mount, by the listing alone, since its real path echoes
 *   the name asked for;
 * - on HFS+, which stores names in NFD, the first write into a new Korean-titled directory is accepted, because both
 *   sides are compared after NFC.
 *
 * The real path also refuses a directory that leads elsewhere without being a link, such as a Windows junction. The
 * listing costs a read of the parent at every step (measured in #361's decision).
 *
 * **Its answer.** The file in its walked directory, or null when a directory on the way is not there and was not made.
 * A path out of bounds is [OutOfBounds], whose reason names the part of the path at fault and never where a link leads:
 * the side that asked says it, and decides what it means.
 */
internal class RecordBound private constructor(
    private val root: Path,
    private val firstNames: Set<String>,
    private val insideFirstName: Boolean,
    private val disk: DiskAnswers,
) {
    /**
     * [target] in its own directory, walked from the real root — each directory made where absent when [creating].
     * Null when one is not there and was not made, or vanished between being made and being looked at.
     */
    fun fileIn(target: Path, creating: Boolean): Path? {
        val names = namesOf(target)
        return walked(names.dropLast(1), creating)?.resolve(names.last())
    }

    /**
     * [directory] itself, walked from the real root and made where absent — as `.ps` is asked for (#386). Null when it
     * vanished between being made and being looked at.
     */
    fun made(directory: Path): Path? = walked(namesOf(directory), creating = true)

    /** [directory] itself, walked without making anything; null when it is not there. */
    fun existing(directory: Path): Path? {
        if (!isThere(directory)) return null
        return walked(namesOf(directory), creating = false)
    }

    /** Relative to the real root, so a reason names the part of the path at fault and never anything beyond it. */
    fun relative(path: Path): String = disk.realPathOf(root).relativize(path).joinToString("/")

    /** Why [file] is no file to read or append to: a link, or anything else that is not a regular file. */
    fun notARegularFile(file: Path): String = "${relative(file)} ${kindOf(file, A_REGULAR_FILE)}"

    // Where the caller was told to look, as RecordLayout names it: below the configured root, through no `.` or `..`,
    // and under the bound.
    private fun namesOf(target: Path): List<String> {
        val names = namesBelowRoot(target.toAbsolutePath()) ?: throw OutOfBounds(OUTSIDE_THE_REPOSITORY)
        if (names.any { it in DOT_NAMES }) throw OutOfBounds(NAMES_A_DOT)
        if (!admitted(names)) throw OutOfBounds(outsideTheBound())
        return names
    }

    private fun namesBelowRoot(absolute: Path): List<String>? {
        if (!absolute.startsWith(root) || absolute.nameCount <= root.nameCount) return null
        return absolute.subpath(root.nameCount, absolute.nameCount).map { it.toString() }
    }

    // `problems/` admits what lies inside it; the root's own level, only the names kept there.
    private fun admitted(names: List<String>): Boolean =
        names.first() in firstNames && (!insideFirstName || names.size > 1)

    private fun walked(names: List<String>, creating: Boolean): Path? {
        var directory = realRoot(creating)
        for (name in names) {
            directory = stepInto(directory.resolve(name), creating) ?: return null
        }
        return directory
    }

    private fun stepInto(directory: Path, creating: Boolean): Path? {
        if (creating && !isThere(directory)) createdOrThere(directory)
        if (!isThere(directory)) return null
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw outOfBounds(directory, kindOf(directory, A_DIRECTORY))
        if (!listedExactly(directory)) throw outOfBounds(directory, NOT_LISTED)
        if (!resolvesToItself(directory)) throw outOfBounds(directory, RESOLVES_ELSEWHERE)
        return directory
    }

    // The name walked is the name on disk: its parent lists it exactly, compared after NFC because HFS+ lists NFD.
    // This refuses a folded alias where a real path echoes the name asked for, as it does in the tracker's image.
    // An exact hit needs no normalizing, and is the usual answer.
    private fun listedExactly(directory: Path): Boolean {
        val name = nfc(directory.fileName.toString())
        val listed = disk.namesIn(directory.parent)
        return name in listed || listed.any { nfc(it) == name }
    }

    // And the real path is the path walked, compared as text after NFC, since Windows' Path.equals ignores case. This
    // refuses a directory that leads elsewhere without being a link, such as a Windows junction.
    private fun resolvesToItself(directory: Path): Boolean =
        nfc(disk.realPathOf(directory).toString()) == nfc(directory.toString())

    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    // Only the root is resolved: it may sit behind a link by configuration, and every name below it is walked.
    private fun realRoot(creating: Boolean): Path {
        if (creating) Files.createDirectories(root)
        return disk.realPathOf(root)
    }

    private fun outOfBounds(directory: Path, what: String) = OutOfBounds("${relative(directory)} $what")

    private fun outsideTheBound(): String {
        if (insideFirstName) return "it lies outside ${firstNames.single()}/"
        return "it is none of the names kept at the root for it: ${firstNames.sorted().joinToString()}"
    }

    companion object {
        private val DOT_NAMES = setOf(".", "..", "")
        private const val OUTSIDE_THE_REPOSITORY = "it lies outside the records repository"
        private const val NAMES_A_DOT = "it names . or .. below the root, which nobody is handed and none may climb by"
        private const val A_DIRECTORY = "a directory"
        private const val NOT_LISTED =
            "is not listed in its directory under exactly that name — the filesystem folds it from another, such as " +
                "a case variant"
        private const val RESOLVES_ELSEWHERE =
            "resolves to another path — a directory that leads elsewhere without being a link, such as a junction"

        /** A problem's files: inside `problems/`, below the root as configured. */
        fun underProblems(layout: RecordLayout, disk: DiskAnswers = DiskAnswers()): RecordBound =
            RecordBound(layout.configuredRoot(), setOf(RecordLayout.PROBLEMS), insideFirstName = true, disk = disk)

        /** Files at the root's own level, under [firstNames] alone — never `.git` or `.ps`. */
        fun underRoot(root: Path, firstNames: Set<String>, disk: DiskAnswers = DiskAnswers()): RecordBound =
            RecordBound(root.toAbsolutePath(), firstNames, insideFirstName = false, disk = disk)
    }
}

/**
 * A path [RecordBound] does not admit, and [reason] why: the part of the path at fault, never where a link leads. No
 * stack is taken — it is an answer, which the side that asked turns into its own refusal.
 */
internal class OutOfBounds(val reason: String) : RuntimeException(reason, null, false, false)

/**
 * What the filesystem answers about a path: its real path, and the names a directory lists (#361). A seam, as
 * [StateDirectory]'s listing is, because the answers differ where the tracker runs. The tracker's image, a Linux
 * container over a macOS bind mount, answers a real path with the name it was asked for; HFS+ lists names in NFD;
 * Windows answers the case on disk. A test plays any of them.
 */
internal class DiskAnswers(
    val realPathOf: (Path) -> Path = { it.toRealPath() },
    val namesIn: (Path) -> Set<String> = ::namesOnDisk,
)

/**
 * [directory] made when absent — by another writer meanwhile too, which is no failure — for the caller to judge after,
 * so a link that won the race is refused there. The one way the records and `.ps` make a directory (#386).
 */
internal fun createdOrThere(directory: Path) {
    runCatching { Files.createDirectory(directory) }.onFailure { if (it !is FileAlreadyExistsException) throw it }
}

/**
 * Whether anything stands at [path], a link included, as the filesystem says (#387). Only "no such file" is no: a name
 * it cannot say about, under a directory that cannot be searched, throws rather than passing for absent — which a
 * reader would take for nothing recorded, and `Files.exists` answers.
 */
internal fun isThere(path: Path): Boolean {
    try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (absent: NoSuchFileException) {
        return false
    }
    return true
}

/** What stands at [path], for a reason: a symbolic link, or not [expected]. */
internal fun kindOf(path: Path, expected: String): String {
    if (Files.isSymbolicLink(path)) return "is a symbolic link"
    return "is not $expected"
}

internal const val A_REGULAR_FILE = "a regular file"
