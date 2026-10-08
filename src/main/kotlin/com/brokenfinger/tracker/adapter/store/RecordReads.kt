package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * How a file a writer keeps at the records repository's own level is read (#387): through [RecordBound], the walk that
 * writer takes, so the reader refuses whatever the writer refuses — a link anywhere on the way or at the file itself, a
 * name the filesystem folds from another, a directory that leads elsewhere such as a junction — and nothing is read
 * from where a link leads.
 *
 * **Why not [ProblemFiles].** That is #354's bound, for content under `problems/` that leaves: it follows a link that
 * stays inside `problems/`, harmless for a read there, and it never throws, because every reader it serves takes
 * absent as absent. A file at the root is appended to or replaced by a writer that follows no link at all, and a reader
 * that read on through a link its writer refused read one file while the writer stopped writing it: a linked `log/`
 * stopped recording (#361) while its lines were still served as the log.
 *
 * **Absent, or refused.** Nothing there — no file, or no directory on the way — is null: the normal path, silent, and
 * nothing is made by looking. A name the filesystem cannot say about, under a directory that cannot be searched, is no
 * such answer and is thrown as it comes. Anything else standing where the file should be is refused — a link, dangling
 * or not, a directory, a FIFO, which is never opened — said once per reason for this instance, naming the path the
 * reader was handed and the part of it at fault, never the content and never where a link leads, and thrown as
 * [RefusedReadException]. What a refusal means is each reader's own to say
 * ([[decisions/2026-10-08-a-refused-read-is-not-an-empty-one]]).
 *
 * **A hard link is read.** It is the file itself rather than a pointer to one, as #354 accepted for reads: git cannot
 * deliver one, and making it takes a shell on this machine. Its writer will not append to one (#361), so a log with a
 * second name is read and not added to — as with any failed append, said at every grading.
 */
internal class RecordReads private constructor(private val bound: RecordBound) {
    private val said = ConcurrentHashMap.newKeySet<String>()

    /** The bytes of [target], or null when nothing is there. Anything else standing there is refused, said and thrown. */
    fun readAllBytes(target: Path): ByteArray? {
        try {
            return bytesOf(target, bounded(target) ?: return null)
        } catch (absent: NoSuchFileException) {
            return null
        }
    }

    // In its directory, walked as its writer walks it with nothing made on the way: null when a directory is not there.
    private fun bounded(target: Path): Path? {
        try {
            return bound.fileIn(target, creating = false)
        } catch (out: OutOfBounds) {
            throw refused(target, out.reason)
        }
    }

    // A regular file, opened without following a link at its own name: one swapped in after the look fails the open
    // rather than being read. A file that is not there throws NoSuchFileException, which is the caller's null.
    private fun bytesOf(target: Path, file: Path): ByteArray {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) throw refused(target, bound.notARegularFile(file))
        return Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readAllBytes() }
    }

    // Once per reason for this instance, naming the path the reader was handed: never content, never a link's target.
    private fun refused(target: Path, reason: String): RefusedReadException {
        if (said.add(reason)) logger.warn(REFUSED_WARNING, target, reason)
        return RefusedReadException(target, reason)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RecordReads::class.java)
        private const val REFUSED_WARNING =
            "Not reading {}: {}. A file the records repository keeps is read through real directories and never " +
                "through a link, as it is written (#387). Said once for this reason."

        /** The reader of files at the root's own level, under [firstNames] alone: the names their writer keeps there. */
        fun underRoot(root: Path, firstNames: Set<String>, disk: DiskAnswers = DiskAnswers()): RecordReads =
            RecordReads(RecordBound.underRoot(root, firstNames, disk))
    }
}

/**
 * A read [RecordReads] refused (#387). Its message names the path the reader was handed and which part of it is not a
 * real directory or file — never the content, and never where a link leads.
 */
internal class RefusedReadException(target: Path, reason: String) :
    FileSystemException(target.toString(), null, reason)
