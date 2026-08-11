package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.OrphanedFrames
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
import java.util.concurrent.ConcurrentHashMap

/**
 * File-backed [RawSessionLog]: one `.jsonl` per live session under [directory]
 * (`.ps/raw` in the record repository, design §5.1).
 *
 * [clock] is injected because the file name carries the start instant — reading the
 * system clock inside would make the name untestable.
 */
class FileRawSessionLog(private val directory: Path, private val clock: Clock = Clock.systemUTC()) : RawSessionLog {
    /** Names this log has handed out. One instance serves every channel, so this is the whole set. */
    private val issued = ConcurrentHashMap.newKeySet<String>()

    /**
     * A name no other session holds.
     *
     * The stamp is millisecond-precise and two gradings can open inside one millisecond —
     * measured 2026-08-11, when two channels for one lesson did exactly that. Both wrote into
     * the same file and one replaced the other on retirement: two gradings interleaved in a
     * single capture, and the only copy of each destroyed (#157).
     *
     * So the name is **issued rather than computed**. A candidate already handed out by this
     * log, or already on disk from an earlier run, gets a discriminator until it is free.
     * Deterministic — no clock precision assumed, no randomness for a test to work around.
     *
     * The file is not created here. Reserving by touching an empty file would leave a
     * frameless session on the work list whenever a process died between the two, and a
     * capture that fails every reconciliation forever is worse than the collision this fixes.
     */
    override fun start(lessonId: Long): RawSessionId {
        require(lessonId > 0) { "lessonId must be positive: $lessonId" }
        return RawSessionId(unusedName("${STAMP.format(clock.instant())}-$lessonId"))
    }

    private fun unusedName(stem: String): String {
        var candidate = "$stem$SUFFIX"
        var discriminator = 1
        while (!issued.add(candidate) || onDisk(candidate)) {
            discriminator += 1
            candidate = "$stem-$discriminator$SUFFIX"
        }
        return candidate
    }

    // Retired sessions count: a name reissued after a restart would write into the copy of a
    // grading already recorded, which is the same loss by a slower route.
    private fun onDisk(name: String): Boolean =
        Files.exists(directory.resolve(name)) || Files.exists(directory.resolve(RETIRED).resolve(name))

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

    // A sub-directory again, for the same reason `setAside` uses one: the work-list walk keeps
    // only direct children whose name parses as a session.
    override fun orphaned(lessonId: Long, frameText: String) {
        val orphans = directory.resolve(ORPHANS)
        Files.createDirectories(orphans)
        Files.writeString(
            orphans.resolve("$lessonId$SUFFIX"),
            frameText.trimEnd('\r', '\n') + "\n",
            CHARSET,
            *APPEND_MODE,
        )
    }

    override fun orphans(): List<OrphanedFrames> {
        val orphans = directory.resolve(ORPHANS)
        if (!Files.isDirectory(orphans)) return emptyList()
        return Files.list(orphans).use { entries ->
            entries.toList().mapNotNull { orphanOf(it) }.sortedBy { it.lessonId }
        }
    }

    // A count of lines, not of gradings: several gradings sit in one file end to end with no
    // separator, and saying "3 gradings" would be a claim this class cannot support.
    private fun orphanOf(file: Path): OrphanedFrames? {
        val lessonId = ORPHAN_NAME.matchEntire(file.fileName.toString())?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val frames = runCatching { Files.readAllLines(file, CHARSET).count { it.isNotBlank() } }.getOrElse { 0 }
        return OrphanedFrames(lessonId, frames, file)
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

        // The trailing group is the collision discriminator `unusedName` adds. Without it here
        // a discriminated session would parse as nothing and drop off the work list entirely —
        // a quieter loss than the collision it exists to prevent.
        private val NAME = Regex("""(\d{8}T\d{9}Z)-(\d+)(?:-\d+)?\.jsonl""")
        private val ORPHAN_NAME = Regex("""(\d+)\.jsonl""")
        private val APPEND_MODE = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        private val CHARSET = StandardCharsets.UTF_8
        private const val SUFFIX = ".jsonl"
        private const val RAW_DIRECTORY = ".ps/raw"

        /** Where a session goes once its record is durable but nothing copied it. */
        const val RETIRED = "recorded"

        /** Where frames belonging to no grading are kept — readable, never replayable. */
        const val ORPHANS = "orphans"

        /** Raw logs live under the record repository, not next to the tool (design §5.1). */
        fun under(recordRoot: Path, clock: Clock = Clock.systemUTC()): FileRawSessionLog =
            FileRawSessionLog(recordRoot.resolve(RAW_DIRECTORY), clock)
    }
}
