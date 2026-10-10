package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/**
 * How a file in the records repository is written when a link could carry the write somewhere else (#361) —
 * the writing half of the bound [ProblemFiles] keeps for reads.
 *
 * **The threat.** Git stores symbolic links, so a clone or a pull can put one anywhere in the repository: where
 * a file the server writes should be, or where a directory it writes into should be. A writer that followed it
 * overwrote, appended to or created whatever the link named. Measured in #354's review: a problem's `README.md`
 * linked to `.ps/git-credentials` was overwritten, a linked `runs.jsonl` had a run's line appended to the token,
 * and a dangling `statement.md` made the statement writer create a file outside `problems/`. Anything the owner
 * can write was in reach, with content drawn partly from the records.
 *
 * **The bound.** Every directory from the real root down to the target's own is walked one name at a time and
 * created where absent, by [RecordBound]: each a real directory, listed by its parent under exactly the name walked,
 * resolving to the path walked. So a write never passes a link, and nothing is created or written wherever one leads.
 * The target must lie below the root as configured, name no `.` or `..`, and fall under the bound — inside
 * `problems/` for a problem's files, and for a writer at the root's own level only the names it keeps there. Unlike a
 * read under `problems/`, a write follows no link at all, even one that stays inside the bound.
 *
 * **The file.** A whole file is written beside its target and moved over it by [FileReplacement], the write the state
 * directory's files share (#386), so a link standing there is replaced rather than followed — said once — and a hard
 * link is broken rather than written through. It keeps the mode of the regular file it replaces, and a new one gets
 * what a plain write gives a new file, unless the writer is owner-only. An appended file must be a regular file or
 * absent, and is opened without following a link; it must also have no second name, where the `unix` view can count a
 * file's names — not on Windows, which offers no such view, so a hard link there is appended to. A file created new is
 * never created where anything stands, a link included.
 *
 * **Said once, never quoted.** A refusal logs one warning naming the path the writer was handed and the reason —
 * once per reason for this instance, never the content and never where a link leads — and throws
 * [RefusedWriteException], unless the writer asked to skip ([replaceOrSkip]). Which writers fail loudly and which
 * carry on is each writer's own posture ([[decisions/2026-10-08-no-writer-follows-a-link]]).
 */
internal class RecordWrites private constructor(private val bound: RecordBound, ownerOnly: Boolean) {
    private val said = SaidOnce()
    private val replacement = FileReplacement(FileMode.forRecords(ownerOnly))

    /** Replaces [target] whole with [text]. A link standing there is replaced, never written through. */
    fun replace(target: Path, text: String) = replaceAt(target, fileIn(target), text)

    /** [replace], or false when it was refused, which has been said — for a writer that carries on without the file. */
    fun replaceOrSkip(target: Path, text: String): Boolean {
        try {
            replace(target, text)
        } catch (refused: RefusedWriteException) {
            return false
        }
        return true
    }

    /** Writes [target] unless a regular file is already there, which is left as it is. A link is no such file. */
    fun writeOnce(target: Path, text: String) {
        val file = fileIn(target)
        if (Files.isRegularFile(file, NOFOLLOW_LINKS)) return
        replaceAt(target, file, text)
    }

    /**
     * Appends [line] and a line break to [target], a regular file with no other name or none, first ending a line a
     * crash cut short.
     */
    fun appendLine(target: Path, line: String) {
        val file = fileIn(target)
        if (isThereButNotAFile(file)) throw refused(target, bound.notARegularFile(file))
        if (hasAnotherName(file)) throw refused(target, "${bound.relative(file)} $IS_A_HARD_LINK")
        Files.writeString(file, healed(file) + line + "\n", CHARSET, CREATE, APPEND, NOFOLLOW_LINKS)
    }

    /** Creates [target] holding [bytes]. Anything already there, a link included, is a [FileAlreadyExistsException]. */
    fun createNew(target: Path, bytes: ByteArray) {
        Files.newOutputStream(fileIn(target), CREATE_NEW, NOFOLLOW_LINKS).use { it.write(bytes) }
    }

    /**
     * Whether [target] is a regular file holding exactly [bytes], its directory walked through no link and nothing
     * made on the way (#403). Anything else there, or a directory on the way that is refused, is not: false.
     */
    fun holds(target: Path, bytes: ByteArray): Boolean {
        val directory = runCatching { bounded(target) { bound.existing(target.toAbsolutePath().parent) } }.getOrNull()
        return directory != null && holdsExactly(directory.resolve(target.fileName), bytes)
    }

    /**
     * Deletes each of [names] in [directory], best effort. A name that is a link is deleted as one; a directory that is
     * not there, or is refused, deletes nothing.
     */
    fun deleteIn(directory: Path, names: Collection<String>) {
        val real = runCatching { bounded(directory) { bound.existing(directory) } }.getOrNull() ?: return
        names.forEach { name -> runCatching { Files.deleteIfExists(real.resolve(name)) } }
    }

    /**
     * Deletes [target] when it is a regular file, its directory walked through no link and nothing made on the way;
     * true when nothing is left there (#387's review). Anything else standing there — a link, a directory — is not this
     * writer's to delete, and is kept: false. A directory on the way that is refused is thrown, as for every write.
     */
    fun removeFile(target: Path): Boolean {
        val directory = bounded(target) { bound.existing(target.toAbsolutePath().parent) } ?: return true
        return removedIfRegular(directory.resolve(target.fileName))
    }

    /**
     * Moves [target] to [name] in its own directory when it is a regular file, the directory walked through no link and
     * nothing made on the way; true when moved (#403). Anything else at [target] is not this writer's to move: false.
     * What already has [name] is never replaced, and a directory on the way that is refused is thrown.
     */
    fun moveAside(target: Path, name: String): Boolean {
        val directory = bounded(target) { bound.existing(target.toAbsolutePath().parent) } ?: return false
        return movedIfRegular(directory.resolve(target.fileName), directory.resolve(name))
    }

    // The target's own directory, walked from the real root and created where absent, then the target's name in it.
    // Null from the walk only when a directory vanished between being made and being looked at.
    private fun fileIn(target: Path): Path =
        bounded(target) { bound.fileIn(target, creating = true) } ?: throw NoSuchFileException("$target")

    // The bound's answer, or a path it does not admit refused as this writer's, said and thrown.
    private fun <T> bounded(target: Path, walk: () -> T): T {
        try {
            return walk()
        } catch (out: OutOfBounds) {
            throw refused(target, out.reason)
        }
    }

    // A directory there is refused and a link is replaced, said: those are this writer's. The write is the shared one.
    private fun replaceAt(target: Path, file: Path, text: String) {
        if (Files.isDirectory(file, NOFOLLOW_LINKS)) throw refused(target, "${bound.relative(file)} $IS_A_DIRECTORY")
        if (isThereButNotAFile(file)) replacing(target, file)
        replacement.replace(file, text)
    }

    private fun replacing(target: Path, file: Path) {
        val kind = kindOf(file, A_REGULAR_FILE)
        said.say("$REPLACED ${bound.relative(file)}") { logger.warn(REPLACED_WARNING, target, kind) }
    }

    // A last line a crash cut short is ended first, so the next is never glued onto it. Read without following a link.
    private fun healed(file: Path): String {
        val size = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).size() }
            .getOrDefault(0L)
        if (size == 0L || lastByteOf(file, size) == NEWLINE) return ""
        return "\n"
    }

    private fun lastByteOf(file: Path, size: Long): Byte = Files.newByteChannel(file, READ, NOFOLLOW_LINKS).use {
        val buffer = ByteBuffer.allocate(1)
        it.position(size - 1).read(buffer)
        buffer.get(0)
    }

    // Present and not a regular file: a link, a directory, a FIFO.
    private fun isThereButNotAFile(file: Path): Boolean =
        Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)

    // A hard link is the file under a second name, so an append would change it there too. Counted only where the
    // `unix` view counts a file's names, which Windows does not offer: there the check does not run.
    private fun hasAnotherName(file: Path): Boolean {
        if (UNIX !in file.fileSystem.supportedFileAttributeViews() || !Files.exists(file, NOFOLLOW_LINKS)) return false
        return (Files.getAttribute(file, LINK_COUNT, NOFOLLOW_LINKS) as Int) > 1
    }

    // Once per reason for this instance, naming the path the writer was handed: never content, never a link's target.
    private fun refused(target: Path, reason: String): RefusedWriteException {
        said.say(reason) { logger.warn(REFUSED_WARNING, target, reason) }
        return RefusedWriteException(target, reason)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RecordWrites::class.java)
        private val CHARSET = StandardCharsets.UTF_8
        private const val UNIX = "unix"
        private const val LINK_COUNT = "unix:nlink"
        private const val NEWLINE = '\n'.code.toByte()
        private const val IS_A_DIRECTORY = "is a directory"
        private const val IS_A_HARD_LINK =
            "is a hard link — another name shares the file, and an append would change it"
        private const val REPLACED = "replaced"
        private const val REFUSED_WARNING =
            "Not writing {}: {}. The records repository is written only through real directories, never through " +
                "a link (#361). Said once for this reason."
        private const val REPLACED_WARNING =
            "Replacing {}, which {}, with the file itself rather than writing through it (#361). " +
                "Said once for this path."

        /** The writer of a problem's files: inside `problems/`, below the root as configured. */
        fun underProblems(
            layout: RecordLayout,
            ownerOnly: Boolean = false,
            disk: DiskAnswers = DiskAnswers(),
        ): RecordWrites = RecordWrites(RecordBound.underProblems(layout, disk), ownerOnly)

        /**
         * The writer of files at the root's own level — the submission log, the tag notes, the seeds, the heartbeat's
         * marker — which writes only under [firstNames], the names it keeps there. Never `.git` or `.ps` (#361).
         */
        fun underRoot(root: Path, firstNames: Set<String>, disk: DiskAnswers = DiskAnswers()): RecordWrites =
            RecordWrites(RecordBound.underRoot(root, firstNames, disk), ownerOnly = false)

        /** [underRoot] for the records repository [layout] lays out. */
        fun underRoot(layout: RecordLayout, firstNames: Set<String>): RecordWrites =
            underRoot(layout.configuredRoot(), firstNames)
    }
}

/**
 * [file] deleted when it is a regular file, never following a link; true when nothing is left there (#387's review).
 * What else stands there is kept, and false.
 */
internal fun removedIfRegular(file: Path): Boolean {
    if (!isThere(file)) return true
    if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return false
    Files.deleteIfExists(file)
    return true
}

/**
 * [file] moved to [aside] when it is a regular file, judged without following a link; true when moved (#403). A file
 * already at [aside] is never replaced: a [java.nio.file.FileAlreadyExistsException].
 */
internal fun movedIfRegular(file: Path, aside: Path): Boolean {
    if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return false
    Files.move(file, aside)
    return true
}

/**
 * [file] a regular file, judged without following a link, holding exactly [bytes] (#403). Opened only when its size
 * is theirs, and read no further than one byte past it, so a FIFO is never opened and a large file never read whole.
 */
internal fun holdsExactly(file: Path, bytes: ByteArray): Boolean = runCatching {
    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    attributes.isRegularFile && attributes.size() == bytes.size.toLong() && bytes.contentEquals(firstOf(file, bytes))
}.getOrDefault(false)

private fun firstOf(file: Path, bytes: ByteArray): ByteArray =
    Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(bytes.size + 1) }

/**
 * A write [RecordWrites] refused (#361). Its message names the path the writer was handed and which part of that
 * path is not a real directory or file — never the content, and never where a link leads.
 */
internal class RefusedWriteException(target: Path, reason: String) :
    FileSystemException(target.toString(), null, reason)
