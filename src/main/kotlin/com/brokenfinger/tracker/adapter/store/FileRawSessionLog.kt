package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.RawSession
import com.brokenfinger.tracker.application.RawSessionId
import com.brokenfinger.tracker.application.RawSessionLog
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * File-backed [RawSessionLog]: one `.jsonl` per live session under [directory]
 * (`.ps/raw` in the record repository, design §5.1).
 *
 * [clock] is injected because the file name carries the start instant — reading the
 * system clock inside would make the name untestable.
 */
class FileRawSessionLog(private val directory: Path, private val clock: Clock = Clock.systemUTC()) : RawSessionLog {
    override fun start(lessonId: Long): RawSessionId {
        require(lessonId > 0) { "lessonId must be positive: $lessonId" }
        return RawSessionId("${STAMP.format(clock.instant())}-$lessonId$SUFFIX")
    }

    override fun append(session: RawSessionId, frameText: String) {
        Files.createDirectories(directory)
        // Written verbatim: re-serializing would silently rewrite whatever Programmers
        // actually sent (dev rules §2.4). Only a trailing line break is dropped, so a
        // frame never opens a blank line; interior breaks stay as they arrived.
        Files.writeString(fileOf(session), frameText.trimEnd('\r', '\n') + "\n", CHARSET, *APPEND_MODE)
    }

    override fun complete(session: RawSessionId, destination: Path): Path {
        val source = fileOf(session)
        if (!Files.exists(source)) throw NoSuchFileException("$source")
        // Never replace: the destination is an attempt file, and overwriting one would
        // destroy frames that can never be captured again (protocol doc §11).
        if (Files.exists(destination)) throw FileAlreadyExistsException("$destination")
        destination.parent?.let { Files.createDirectories(it) }
        return Files.copy(source, destination)
    }

    override fun discard(session: RawSessionId) {
        Files.deleteIfExists(fileOf(session))
    }

    // A sub-directory, so `unprocessed` stops seeing it: that walk keeps only direct children
    // whose name parses as a session, and a directory never does.
    override fun setAside(session: RawSessionId) {
        val source = fileOf(session)
        if (!Files.exists(source)) return
        val retired = directory.resolve(RETIRED)
        Files.createDirectories(retired)
        Files.move(source, retired.resolve(session.value), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun unprocessed(): List<RawSession> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { entries ->
            entries.toList().mapNotNull { sessionOf(it) }.sortedBy { it.id.value }
        }
    }

    private fun sessionOf(file: Path): RawSession? {
        val name = file.fileName.toString()
        val (stamp, lessonId) = NAME.matchEntire(name)?.destructured ?: return null
        return RawSession(RawSessionId(name), lessonId.toLong(), instantOf(stamp), file)
    }

    private fun instantOf(stamp: String): Instant = LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC)

    private fun fileOf(session: RawSessionId): Path = directory.resolve(session.value)

    companion object {
        // Basic ISO, UTC, millisecond precision: sortable as text and colon-free, because
        // Windows rejects a colon in a file name and CI runs windows-latest.
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
        private val NAME = Regex("""(\d{8}T\d{9}Z)-(\d+)\.jsonl""")
        private val APPEND_MODE = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        private val CHARSET = StandardCharsets.UTF_8
        private const val SUFFIX = ".jsonl"
        private const val RAW_DIRECTORY = ".ps/raw"

        /** Where a session goes once its record is durable but nothing copied it. */
        const val RETIRED = "recorded"

        /** Raw logs live under the record repository, not next to the tool (design §5.1). */
        fun under(recordRoot: Path, clock: Clock = Clock.systemUTC()): FileRawSessionLog =
            FileRawSessionLog(recordRoot.resolve(RAW_DIRECTORY), clock)
    }
}
