package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.OrphanedFrames
import com.brokenfinger.tracker.application.RawSession
import com.brokenfinger.tracker.application.RawSessionId
import com.brokenfinger.tracker.application.RawSessionLog
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * File-backed [RawSessionLog]: one `.jsonl` per live session under [directory]
 * (`.ps/raw` in the record repository, design §5.1).
 *
 * [clock] is injected because the file name carries the start instant — reading the
 * system clock inside would make the name untestable.
 *
 * **Under the record repository ([under]) it writes only into the real state directory, and never
 * discards a frame for it (#360).** Its [guard] is asked at a session's first frame — once, because
 * asking asks git, and a grading sends a frame per testcase — and again at the next frame when the
 * refusal was transient, such as a read that failed: such a refusal is never kept for the session.
 * Before every write the directory itself is checked — `.ps`, `raw`, `recorded`, `orphans`, none of
 * them a link — which costs a stat each, so a link swapped in between two frames is never written
 * through.
 *
 * While the state directory is refused, frames are **held in memory** instead, within [heldLimit]
 * characters across the log. A submit's frames go to its attempt file at [complete], which lies outside
 * `.ps`; a run set aside and an orphan are written into `.ps` the first time it is usable again, and
 * [close] says what is still held when the server stops. Each refusal, and the limit, is said once.
 *
 * **The copy beside the record goes through [RecordWrites] when the log knows its [recordRoot]** — as
 * [under], and so the composition root, builds it (#361). The copy never replaced anything, a link
 * included; the directories above it are now never made or passed through a link either. A refusal is
 * thrown, and the writer keeps the frames with the runs, as it does for any copy that fails. Built bare,
 * as the guard can be, it writes where it is told.
 */
class FileRawSessionLog(
    private val directory: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val guard: StateDirectory? = null,
    private val heldLimit: Long = HELD_LIMIT,
    recordRoot: Path? = null,
) : RawSessionLog,
    AutoCloseable {
    /** Where a submit's frames are copied: under the record repository's `problems/`, through no link. */
    private val attempts = recordRoot?.let { RecordWrites.underProblems(RecordLayout(it)) }

    /** Names this log has handed out. One instance serves every channel, so this is the whole set. */
    private val issued = ConcurrentHashMap.newKeySet<String>()

    /** The sessions this instance is writing: where each one's frames go, and those it holds. */
    private val live = ConcurrentHashMap<String, LiveSession>()

    /** Runs set aside and orphans kept while `.ps` was refused, written once it is usable again. */
    private val heldRuns = ConcurrentHashMap<String, List<String>>()
    private val heldOrphans = ConcurrentHashMap<Long, MutableList<String>>()

    private val heldChars = AtomicLong()

    private val said = ConcurrentHashMap.newKeySet<String>()

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
        val line = lineOf(frameText)
        val state = live.computeIfAbsent(session.value) { LiveSession() }
        synchronized(state) {
            val raw = destinationOf(state) ?: return hold(state.frames, line)
            writeHeldFirst(raw.resolve(session.value), state)
            appended(raw.resolve(session.value), line, state)
        }
    }

    override fun complete(session: RawSessionId, destination: Path): Path {
        // Never replace: the destination is an attempt file, and overwriting one would
        // destroy frames that can never be captured again (protocol doc §11).
        if (Files.exists(destination)) throw FileAlreadyExistsException("$destination")
        val state = live[session.value]
        val onDisk = framesOnDisk(session, state)
        val held = state?.let { synchronized(it) { it.frames.toList() } }.orEmpty()
        if (onDisk == null && held.isEmpty()) throw NoSuchFileException("${directory.resolve(session.value)}")
        return written(destination, framesOf(onDisk, held))
    }

    override fun discard(session: RawSessionId) {
        live.remove(session.value)?.let { released(it.frames) }
        rawDirectory()?.let { Files.deleteIfExists(it.resolve(session.value)) }
    }

    // A sub-directory, so `unprocessed` stops seeing it: that walk keeps only direct children
    // whose name parses as a session, and a directory never does. Whatever is held goes with it,
    // or waits in memory while `.ps` is refused.
    override fun setAside(session: RawSessionId) {
        val held = live.remove(session.value)?.let { synchronized(it) { it.frames.toList() } }.orEmpty()
        val recorded = writable(RETIRED) ?: return holdRun(session.value, held)
        rawDirectory()?.resolve(session.value)?.takeIf { Files.exists(it) }?.let {
            Files.move(it, recorded.resolve(session.value), StandardCopyOption.REPLACE_EXISTING)
        }
        appendedAll(recorded.resolve(session.value), held)
        releaseHeld()
    }

    // A sub-directory again, for the same reason `setAside` uses one: the work-list walk keeps
    // only direct children whose name parses as a session.
    override fun orphaned(lessonId: Long, frameText: String) {
        val line = lineOf(frameText)
        val orphans = writable(ORPHANS) ?: return hold(heldOrphans.computeIfAbsent(lessonId) { orphanList() }, line)
        releaseHeld()
        appendLine(orphans.resolve("$lessonId$SUFFIX"), line)
    }

    /**
     * What is still held when the server stops. Written into `.ps` if it is usable now — a live
     * session's frames onto the work list, where the next start replays them — and said otherwise,
     * because memory does not outlive the process.
     */
    override fun close() {
        if (guard?.forWriting() is StateDirectory.Usable) flushEverything()
        val left = live.values.sumOf { it.frames.size } + heldRuns.values.sumOf { it.size } +
            heldOrphans.values.sumOf { it.size }
        if (left > 0) logger.warn(LOST_AT_EXIT, left)
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
        val raw = listable() ?: return emptyList()
        return Files.list(raw).use { entries ->
            entries.toList().mapNotNull { sessionOf(it) }.sortedBy { it.id.value }
        }
    }

    // Listed only through real directories — `.ps` and `raw`, neither a link (#387). Replaying a session makes a record,
    // and one behind a link is not the tracker's own. Said once; what is there waits for a boot that can list it.
    private fun listable(): Path? {
        val guard = guard ?: return directory
        return when (val inspection = guard.pathFor(RAW)) {
            is StateDirectory.Usable -> inspection.directory
            is StateDirectory.Refused -> unlisted(inspection.refusal)
        }
    }

    private fun unlisted(refusal: StateDirectory.Refusal): Path? {
        sayOnce(UNLISTED) { logger.warn(NOT_LISTED, refusal.reason) }
        return null
    }

    private fun sessionOf(file: Path): RawSession? {
        val name = file.fileName.toString()
        val (stamp, lessonId) = NAME.matchEntire(name)?.destructured ?: return null
        return RawSession(RawSessionId(name), lessonId.toLong(), instantOf(stamp), file)
    }

    private fun instantOf(stamp: String): Instant = LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC)

    private fun fileOf(session: RawSessionId): Path = directory.resolve(session.value)

    private fun lineOf(frameText: String): String = frameText.trimEnd('\r', '\n')

    // Where this session's frames go now: the raw directory, or null while they are held.
    private fun destinationOf(state: LiveSession): Path? {
        if (state.verdict == Verdict.UNDECIDED) state.verdict = decided()
        if (state.verdict != Verdict.DISK) return null
        return rawDirectory()
    }

    // A transient refusal leaves the session undecided, so its next frame asks again.
    private fun decided(): Verdict = when (val inspection = guard?.forWriting()) {
        null, is StateDirectory.Usable -> Verdict.DISK.also { releaseHeld() }
        is StateDirectory.Refused -> refused(inspection.refusal)
    }

    private fun refused(refusal: StateDirectory.Refusal): Verdict {
        sayOnce(refusal.name) { logger.warn(HELD, refusal.reason) }
        if (refusal.transient) return Verdict.UNDECIDED
        return Verdict.MEMORY
    }

    // Written in arrival order: what was held while `.ps` was refused goes before the frame at hand.
    private fun writeHeldFirst(file: Path, state: LiveSession) {
        while (state.frames.isNotEmpty()) {
            appended(file, state.frames.first(), state)
            released(listOf(state.frames.removeAt(0)))
        }
    }

    private fun appended(file: Path, line: String, state: LiveSession) {
        appendLine(file, line)
        state.wroteToDisk = true
    }

    // Written verbatim: re-serializing would silently rewrite whatever Programmers actually sent (dev
    // rules §2.4). Only a trailing line break is dropped, so a frame never opens a blank line; interior
    // breaks stay as they arrived. Opened without following a link.
    private fun appendLine(file: Path, line: String) {
        Files.writeString(file, line + "\n", CHARSET, *APPEND_MODE)
    }

    private fun appendedAll(file: Path, lines: List<String>) {
        lines.forEach { appendLine(file, it) }
        released(lines)
    }

    // Bounded across the whole log: past the limit a frame is dropped, and that is said once.
    private fun hold(frames: MutableList<String>, line: String) {
        if (heldChars.addAndGet(line.length.toLong()) <= heldLimit) {
            synchronized(frames) { frames += line }
            return
        }
        heldChars.addAndGet(-line.length.toLong())
        sayOnce(LIMIT) { logger.warn(OVER_LIMIT, heldLimit) }
    }

    private fun holdRun(name: String, frames: List<String>) {
        if (frames.isNotEmpty()) heldRuns.merge(name, frames) { old, new -> old + new }
    }

    private fun released(lines: List<String>) {
        heldChars.addAndGet(-lines.sumOf { it.length.toLong() })
    }

    // Runs and orphans kept while `.ps` was refused, now that it is usable (#360).
    private fun releaseHeld() {
        if (heldRuns.isEmpty() && heldOrphans.isEmpty()) return
        subdirectory(RETIRED)?.let { recorded ->
            heldRuns.keys.forEach { name -> heldRuns.remove(name)?.let { appendedAll(recorded.resolve(name), it) } }
        }
        subdirectory(ORPHANS)?.let { orphans ->
            heldOrphans.keys.forEach { id ->
                heldOrphans.remove(id)?.let { held ->
                    appendedAll(orphans.resolve("$id$SUFFIX"), synchronized(held) { held.toList() })
                }
            }
        }
    }

    private fun flushEverything() {
        val raw = rawDirectory() ?: return
        live.forEach { (name, state) -> synchronized(state) { writeHeldFirst(raw.resolve(name), state) } }
        releaseHeld()
    }

    // A session this instance wrote to disk must be read back from there, safely, or not at all; one it
    // never saw — a replay after a restart — is read if it is there.
    private fun framesOnDisk(session: RawSessionId, state: LiveSession?): ByteArray? {
        val raw = rawDirectory()
        if (raw == null && state?.wroteToDisk == true) throw unreachable()
        if (raw == null) return null
        val file = raw.resolve(session.value)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readAllBytes() }
    }

    // What was on disk, then what was held, in arrival order.
    private fun framesOf(onDisk: ByteArray?, held: List<String>): ByteArray =
        (onDisk ?: ByteArray(0)) + held.joinToString("") { "$it\n" }.toByteArray(CHARSET)

    private fun written(destination: Path, frames: ByteArray): Path {
        val bounded = attempts ?: return unbounded(destination, frames)
        bounded.createNew(destination, frames)
        return destination
    }

    private fun unbounded(destination: Path, frames: ByteArray): Path {
        destination.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { it.write(frames) }
        return destination
    }

    private fun unreachable() = java.io.IOException("the raw directory under .ps is not a real directory")

    /** The raw directory, checked before every write: none of it a link (#360). */
    private fun rawDirectory(): Path? = subdirectory(null)

    // [sub] below the raw directory, or the raw directory itself.
    private fun subdirectory(sub: String?): Path? {
        val guard = guard ?: return Files.createDirectories(sub?.let(directory::resolve) ?: directory)
        return when (val inspection = guard.pathFor(*listOfNotNull(RAW, sub).toTypedArray())) {
            is StateDirectory.Usable -> inspection.directory
            is StateDirectory.Refused -> null.also { refused(inspection.refusal) }
        }
    }

    // For a write that belongs to no live session: `.ps` usable now, and the directory a real one.
    private fun writable(sub: String): Path? = when (val inspection = guard?.forWriting()) {
        null, is StateDirectory.Usable -> subdirectory(sub)
        is StateDirectory.Refused -> null.also { refused(inspection.refusal) }
    }

    private fun sayOnce(key: String, warn: () -> Unit) {
        if (said.add(key)) warn()
    }

    private fun orphanList(): MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private class LiveSession {
        var verdict = Verdict.UNDECIDED
        var wroteToDisk = false
        val frames = mutableListOf<String>()
    }

    private enum class Verdict { UNDECIDED, DISK, MEMORY }

    companion object {
        // Basic ISO, UTC, millisecond precision: sortable as text and colon-free, because
        // Windows rejects a colon in a file name and CI runs windows-latest.
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

        // The trailing group is the collision discriminator `unusedName` adds. Without it here
        // a discriminated session would parse as nothing and drop off the work list entirely —
        // a quieter loss than the collision it exists to prevent.
        private val NAME = Regex("""(\d{8}T\d{9}Z)-(\d+)(?:-\d+)?\.jsonl""")
        private val ORPHAN_NAME = Regex("""(\d+)\.jsonl""")
        private val APPEND_MODE: Array<OpenOption> =
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
        private val CHARSET = StandardCharsets.UTF_8
        private const val SUFFIX = ".jsonl"
        private const val RAW = "raw"
        private const val HELD =
            "Raw frames are held in memory rather than written: {}. A submit's still reach its attempt " +
                "file; runs and orphans are written once .ps is usable again, and lost if the server " +
                "stops first. Said once for this reason."
        private const val OVER_LIMIT =
            "Raw frames held in memory reached {} characters; frames beyond that are dropped until " +
                ".ps is usable again. Said once."
        private const val LOST_AT_EXIT =
            "Raw frames held in memory were lost when the server stopped, because .ps was not usable: {} of them."
        private const val LIMIT = "limit"
        private const val UNLISTED = "unlisted"
        private const val NOT_LISTED =
            "The raw work list was not read: {}. Its sessions are replayed once it is a real directory again. " +
                "Said once."

        /** What the log holds in memory at most, across every session, while `.ps` is refused. */
        const val HELD_LIMIT = 8_000_000L

        private val logger = LoggerFactory.getLogger(FileRawSessionLog::class.java)

        /** Where a session goes once its record is durable but nothing copied it. */
        const val RETIRED = "recorded"

        /** Where frames belonging to no grading are kept — readable, never replayable. */
        const val ORPHANS = "orphans"

        /** Raw logs live under the record repository, not next to the tool (design §5.1). */
        fun under(recordRoot: Path, clock: Clock, state: StateDirectory): FileRawSessionLog = FileRawSessionLog(
            recordRoot.resolve(StateDirectory.NAME).resolve(RAW),
            clock,
            state,
            recordRoot = recordRoot,
        )
    }
}
