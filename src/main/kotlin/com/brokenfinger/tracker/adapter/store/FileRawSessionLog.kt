package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.LeftUnreplayed
import com.brokenfinger.tracker.application.OrphanedFrames
import com.brokenfinger.tracker.application.Orphans
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
import java.nio.file.attribute.BasicFileAttributes
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
 * **So the answer a session's first frame got holds for that grading alone (#377).** Asking git at every
 * frame would cost a median of 7–8 ms a frame against 0.03 ms for the append itself (measured on the host,
 * 500 to 5,000 index entries). What a pull can change unseen meanwhile is whether git tracks something else
 * under `.ps`: the grading in flight then finishes in its own file, which git does not track unless the pull
 * delivered that very name, which carries the millisecond the grading started. The next session asks again.
 *
 * While the state directory is refused, frames are **held in memory** instead, within [heldLimit]
 * characters across the log. A submit's frames go to its attempt file at [complete], which lies outside
 * `.ps`; a run set aside and an orphan are written into `.ps` the first time it is usable again, and
 * [close] says what is still held when the server stops. Each refusal, and the limit, is said once. Runs set
 * aside and orphans hold three quarters of the limit at most, so a submit in flight always has room (#378); the
 * full limit of 8,000,000 characters retains about 16 MB of heap (measured with the fixtures' frames, whose Korean
 * makes each character two bytes).
 *
 * **A boot replays only what a write would accept (#377).** [unprocessed] lists the work list only while
 * the [guard] would let a frame be written there: what it refuses is left where it is, unread, and said
 * once with how many. Only regular files are listed, and never a session git has ever tracked, under any
 * spelling: untracked, one a pull delivered reads like the tracker's own. Git is asked once, and only when a
 * session waits; while it cannot say, nothing is replayed.
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
    private val orphanBytes: Long = ORPHAN_BYTES,
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

    /**
     * What runs set aside and orphans may hold, of [heldLimit], so that a quarter always stays for the gradings in
     * flight: settled frames filled the budget and a submit's were dropped, its attempt left with no raw copy (#378).
     */
    private val settledLimit = heldLimit - heldLimit / LIVE_SHARE
    private val settledChars = AtomicLong()

    private val said = ConcurrentHashMap.newKeySet<String>()

    /** What the last [unprocessed] left on the work list, for the history's readers (#377). */
    @Volatile
    private var left = LeftUnreplayed.NOTHING

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
        val orphans = writable(ORPHANS) ?: return holdOrphan(lessonId, line)
        releaseHeld()
        val file = orphans.resolve("$lessonId$SUFFIX")
        if (isThereButNotAFile(file)) return heldBehind(lessonId, line)
        appendLine(file, line)
    }

    private fun holdOrphan(lessonId: Long, line: String) =
        holdSettled(heldOrphans.computeIfAbsent(lessonId) { orphanList() }, line)

    // Anything but a regular file where a lesson's orphans go — a link, most likely — loses no frame (#378): it is held
    // like a refused frame and written once a regular file or nothing stands there. The link is never replaced: the
    // file is append-only, and a link there stands for history a replacement would drop. Said once, never where.
    private fun heldBehind(lessonId: Long, line: String) {
        sayOnce(ORPHAN_NOT_A_FILE_KEY) { logger.warn(ORPHAN_NOT_A_FILE) }
        holdOrphan(lessonId, line)
    }

    private fun isThereButNotAFile(file: Path): Boolean =
        Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)

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

    /**
     * The orphans, read only where a frame could be written now, as [unprocessed] reads the work list (#378): `.ps`
     * the tracker's own with git tracking nothing there, then no link on the way, checked after git answers. Only a
     * regular file no larger than [orphanBytes] is opened, without following a link, and never one git has ever
     * tracked. A FIFO, a device or a link is never read: one hung the boot and every MCP call, another reported a
     * file outside as orphaned frames (the review of #387, measured). What is passed over is counted, and an
     * orphans directory that could not be listed is said to be, so a refusal never reads as a whole history.
     */
    override fun orphans(): Orphans {
        if (!Files.isDirectory(directory.resolve(ORPHANS))) return Orphans.NONE
        val guard = guard ?: return orphansIn(namedLikeOrphans(directory.resolve(ORPHANS)), known = emptySet())
        val state = guard.forWriting()
        if (state is StateDirectory.Refused) return orphansNotRead(state.refusal, guard.pathFor(RAW, ORPHANS))
        return when (val orphans = guard.pathFor(RAW, ORPHANS)) {
            is StateDirectory.Usable -> orphansUnknownToGit(namedLikeOrphans(orphans.directory), guard)
            is StateDirectory.Refused -> orphansNotListed(orphans.refusal)
        }
    }

    // Under a refused state directory the orphans are counted by name where no link is on the way, never opened.
    private fun orphansNotRead(refusal: StateDirectory.Refusal, inspection: StateDirectory.Inspection): Orphans {
        val orphans = (inspection as? StateDirectory.Usable)?.directory ?: return orphansNotListed(refusal)
        val named = namedLikeOrphans(orphans).size
        if (named > 0) sayOnce("$ORPHANS_KEY${refusal.name}") { logger.warn(ORPHANS_NOT_READ, named, refusal.reason) }
        return Orphans(emptyList(), named, unlisted = false)
    }

    private fun orphansNotListed(refusal: StateDirectory.Refusal): Orphans {
        sayOnce("$ORPHANS_KEY${refusal.name}") { logger.warn(ORPHANS_NOT_LISTED, refusal.reason) }
        return Orphans(emptyList(), 0, unlisted = true)
    }

    // Git is asked what it has ever tracked only when a file waits to be read; unanswered, none is read.
    private fun orphansUnknownToGit(named: List<Path>, guard: StateDirectory): Orphans {
        if (named.isEmpty()) return Orphans.NONE
        return when (val history = guard.pathsEverTracked()) {
            is TrackedHistory.Known -> orphansIn(named, history.paths)
            is TrackedHistory.Unanswered -> orphansUnanswered(named.size, history.reason)
        }
    }

    private fun orphansUnanswered(named: Int, reason: String): Orphans {
        sayOnce(ORPHANS_UNANSWERED_KEY) { logger.warn(ORPHANS_UNANSWERED, named, reason) }
        return Orphans(emptyList(), named, unlisted = false)
    }

    // A file git has known is neither read nor counted: what git delivered is no gap in what this server captured,
    // and counting it would mark every answer for good. It is said once instead (the review of PR #395).
    private fun orphansIn(named: List<Path>, known: Set<String>): Orphans {
        val (delivered, ours) = named.partition { file -> known.any { isPath(it, RAW, ORPHANS, "${file.fileName}") } }
        if (delivered.isNotEmpty()) sayOnce(ORPHANS_KNOWN_KEY) { logger.warn(ORPHANS_KNOWN, delivered.size) }
        val read = ours.mapNotNull { orphanOf(it) }.sortedBy { it.lessonId }
        val unread = ours.size - read.size
        if (unread > 0) sayOnce(ORPHANS_PASSED_KEY) { logger.warn(ORPHANS_PASSED_OVER, unread, orphanBytes) }
        return Orphans(read, unread, unlisted = false)
    }

    // A count of lines, not of gradings: several gradings sit in one file end to end with no
    // separator, and saying "3 gradings" would be a claim this class cannot support.
    private fun orphanOf(file: Path): OrphanedFrames? {
        val lessonId = lessonIdOf(file) ?: return null
        if (!isAReadableOrphan(file)) return null
        val frames = runCatching { framesIn(file) }.getOrNull() ?: return null
        return OrphanedFrames(lessonId, frames, file)
    }

    // Names only: an entry whose name is a lesson's, whatever it is. Nothing is opened here.
    private fun namedLikeOrphans(orphans: Path): List<Path> =
        Files.list(orphans).use { entries -> entries.toList().filter { lessonIdOf(it) != null } }

    private fun lessonIdOf(file: Path): Long? =
        ORPHAN_NAME.matchEntire(file.fileName.toString())?.groupValues?.get(1)?.toLongOrNull()

    // A regular file, judged without following a link, and small enough to count: never a FIFO, a device or a link.
    private fun isAReadableOrphan(file: Path): Boolean = runCatching {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attributes.isRegularFile && attributes.size() <= orphanBytes
    }.getOrDefault(false)

    // Counted line by line and opened without following a link, so one swapped in after the check fails here.
    private fun framesIn(file: Path): Int = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)
        .bufferedReader(CHARSET)
        .useLines { lines -> lines.count { it.isNotBlank() } }

    /**
     * The work list, read only from where a frame would be written now (#377): `.ps` the tracker's own,
     * git tracking nothing there, no link on the way — what [StateDirectory.forWriting] and
     * [StateDirectory.pathFor] answer every write. A pull can deliver a file whose name parses as a session,
     * and a replay records it as a grading of the owner's. Otherwise nothing is read: each session is left
     * where it is, never moved or deleted, since it may be the owner's own. Git is asked first and the links
     * checked after, just before the listing, so a link swapped in while git answers is not listed through.
     * What is left is kept for [unreplayed] until the next call.
     */
    override fun unprocessed(): List<RawSession> {
        val work = workList()
        left = work.left
        return work.replayable
    }

    override fun unreplayed(): LeftUnreplayed = left

    private fun workList(): WorkList {
        if (!Files.isDirectory(directory)) return WorkList(emptyList(), LeftUnreplayed.NOTHING)
        val guard = guard ?: return sessionsIn(directory).let { it.keeping(it.files) }
        val state = guard.forWriting()
        if (state is StateDirectory.Refused) return leftInPlace(state.refusal, guard.pathFor(RAW))
        return when (val raw = guard.pathFor(RAW)) {
            is StateDirectory.Usable -> unknownToGit(sessionsIn(raw.directory), guard)
            is StateDirectory.Refused -> leftInPlace(raw.refusal, raw)
        }
    }

    // A session git has ever tracked, in any spelling, may be what a pull delivered, and untracking it leaves the
    // file behind: never replayed (#377). One question to git, only when a session waits; unanswered, none is.
    private fun unknownToGit(listed: Listing, guard: StateDirectory): WorkList {
        if (listed.files.isEmpty()) return listed.keeping(emptyList())
        return when (val history = guard.pathsEverTracked()) {
            is TrackedHistory.Known -> notDelivered(listed, history.paths)
            is TrackedHistory.Unanswered -> unanswered(listed, history.reason)
        }
    }

    // A session git has known is never replayed, and not counted as left: git delivered it, so it is no gap in what
    // this server captured, and counting it would mark every answer for good (the review of PR #395). Said once.
    private fun notDelivered(listed: Listing, known: Set<String>): WorkList {
        val (delivered, ours) = listed.files.partition { session -> known.any { isPath(it, RAW, session.id.value) } }
        if (delivered.isNotEmpty()) sayOnce(KNOWN) { logger.warn(KNOWN_TO_GIT, delivered.size) }
        return listed.without(delivered).keeping(ours)
    }

    private fun unanswered(listed: Listing, reason: String): WorkList {
        sayOnce(UNANSWERED) { logger.warn(HISTORY_UNANSWERED, listed.files.size, reason) }
        return listed.keeping(emptyList())
    }

    // [path], below the state directory, is [expected] segment by segment in any case: git keeps a name as it was
    // committed, and a filesystem that folds case answers it for the tracker's own.
    private fun isPath(path: String, vararg expected: String): Boolean {
        val segments = path.split('/')
        if (segments.size != expected.size) return false
        return segments.zip(expected).all { (actual, wanted) -> actual.equals(wanted, ignoreCase = true) }
    }

    // Regular files alone (#377): the tracker writes no link there, so one named like a session is not its own,
    // and is never read through. Those passed over are said once, by how many, and counted as left.
    private fun sessionsIn(raw: Path): Listing {
        val (files, others) = namedLikeSessions(raw).partition(::isARegularFile)
        if (others.isNotEmpty()) sayOnce(NOT_A_FILE) { logger.warn(NOT_REGULAR_FILES, others.size) }
        return Listing(files.sortedBy { it.id.value }, others.size)
    }

    private fun isARegularFile(session: RawSession): Boolean =
        Files.isRegularFile(session.path, LinkOption.NOFOLLOW_LINKS)

    private fun namedLikeSessions(raw: Path): List<RawSession> =
        Files.list(raw).use { entries -> entries.toList().mapNotNull { sessionOf(it) } }

    // Said once with how many and why. Counted only where the directory was inspected and no link is on the way.
    private fun leftInPlace(refusal: StateDirectory.Refusal, raw: StateDirectory.Inspection): WorkList {
        val listed = (raw as? StateDirectory.Usable)?.let { sessionsIn(it.directory) } ?: return notCounted(refusal)
        if (listed.files.isNotEmpty()) {
            sayOnce("$NOT_REPLAYED${refusal.name}") { logger.warn(LEFT_IN_PLACE, listed.files.size, refusal.reason) }
        }
        return listed.keeping(emptyList())
    }

    // Nothing is listed through a link or where the directory could not be inspected, so nothing is counted, and
    // the message names no link that may not be there.
    private fun notCounted(refusal: StateDirectory.Refusal): WorkList {
        sayOnce("$NOT_REPLAYED${refusal.name}") { logger.warn(NOT_COUNTED, refusal.reason) }
        return WorkList(emptyList(), LeftUnreplayed(0, uncounted = true))
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
        if (reserved(line.length.toLong())) {
            synchronized(frames) { frames += line }
            return
        }
        sayOnce(LIMIT) { logger.warn(OVER_LIMIT, heldLimit) }
    }

    // A frame whose grading has settled — an orphan — holds within the settled share, and that limit is said once.
    private fun holdSettled(frames: MutableList<String>, line: String) {
        if (reservedSettled(line.length.toLong())) {
            synchronized(frames) { frames += line }
            return
        }
        sayOnce(SETTLED_LIMIT_KEY) { logger.warn(OVER_SETTLED_LIMIT, settledLimit, heldLimit - settledLimit) }
    }

    // A run set aside moves its frames, held already, into the settled share; past that share they are dropped.
    private fun holdRun(name: String, frames: List<String>) {
        if (frames.isEmpty()) return
        if (settledChars.addAndGet(charsOf(frames)) <= settledLimit) {
            heldRuns.merge(name, frames) { old, new -> old + new }
            return
        }
        settledChars.addAndGet(-charsOf(frames))
        released(frames)
        sayOnce(SETTLED_LIMIT_KEY) { logger.warn(OVER_SETTLED_LIMIT, settledLimit, heldLimit - settledLimit) }
    }

    private fun reserved(chars: Long): Boolean {
        if (heldChars.addAndGet(chars) <= heldLimit) return true
        heldChars.addAndGet(-chars)
        return false
    }

    private fun reservedSettled(chars: Long): Boolean {
        val withinShare = settledChars.addAndGet(chars) <= settledLimit
        if (withinShare && reserved(chars)) return true
        settledChars.addAndGet(-chars)
        return false
    }

    private fun released(lines: List<String>) {
        heldChars.addAndGet(-charsOf(lines))
    }

    private fun charsOf(lines: List<String>): Long = lines.sumOf { it.length.toLong() }

    // Settled frames written at last leave both counts.
    private fun appendedSettled(file: Path, lines: List<String>) {
        appendedAll(file, lines)
        settledChars.addAndGet(-charsOf(lines))
    }

    // Runs and orphans kept while `.ps` was refused, now that it is usable (#360).
    private fun releaseHeld() {
        if (heldRuns.isEmpty() && heldOrphans.isEmpty()) return
        subdirectory(RETIRED)?.let { recorded ->
            heldRuns.keys.forEach { name -> heldRuns.remove(name)?.let { appendedSettled(recorded.resolve(name), it) } }
        }
        subdirectory(ORPHANS)?.let { orphans -> heldOrphans.keys.forEach { id -> releaseOrphans(orphans, id) } }
    }

    // A lesson whose orphans file is a link keeps its frames held: written through it, they would land where it
    // leads, and the throw would stop whichever grading's frame asked for the release (#378).
    private fun releaseOrphans(orphans: Path, lessonId: Long) {
        val file = orphans.resolve("$lessonId$SUFFIX")
        if (isThereButNotAFile(file)) return
        heldOrphans.remove(lessonId)?.let { held -> appendedSettled(file, synchronized(held) { held.toList() }) }
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

    // Named like sessions in a raw directory: the regular files, and how many others were passed over.
    private class Listing(val files: List<RawSession>, val others: Int) {
        // [replayable] replayed, and everything else listed here left on the work list, counted in one place.
        fun keeping(replayable: List<RawSession>) =
            WorkList(replayable, LeftUnreplayed(files.size - replayable.size + others, uncounted = false))

        // The same listing less [these], which leave the count altogether.
        fun without(these: List<RawSession>) = Listing(files - these.toSet(), others)
    }

    // What a start replays, and what it leaves on the work list for a later one.
    private class WorkList(val replayable: List<RawSession>, val left: LeftUnreplayed)

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
        private const val LEFT_IN_PLACE =
            "{} raw session(s) were left in place, not replayed: {}. Each is replayed as a grading at the first " +
                "start that finds .ps usable, unless git has ever tracked it. Said once for this reason."
        private const val NOT_COUNTED =
            "Raw sessions were not replayed: {}. Their directory was not listed, so nothing in it was read or " +
                "counted, and it is left as it is. Said once for this reason."
        private const val KNOWN_TO_GIT =
            "{} raw session(s) were left in place and will never be replayed: git has tracked their names under " +
                ".ps, so each may be what a pull delivered rather than a grading this server captured. They are not " +
                "counted as gaps in the history, and stay where they are for a person to read or delete. Said once."
        private const val HISTORY_UNANSWERED =
            "{} raw session(s) were left in place, not replayed: git could not say what it has ever tracked under " +
                ".ps ({}), and a session git delivered must never be replayed. They are replayed at the first start " +
                "where git answers. Said once."
        private const val KNOWN = "known to git"
        private const val UNANSWERED = "history unanswered"
        private const val NOT_REGULAR_FILES =
            "{} raw session(s) were not replayed because each is not a regular file — a link, most likely, which " +
                "this server never writes there — and none was read. Said once."
        private const val NOT_A_FILE = "not a file"
        private const val ORPHANS_NOT_READ =
            "{} orphaned-frame file(s) were counted but not read: {}. Their frames are counted once .ps is usable. " +
                "Said once for this reason."
        private const val ORPHANS_NOT_LISTED =
            "Orphaned frames were not listed: {}. Their directory was not read, so nothing in it was counted. " +
                "Said once for this reason."
        private const val ORPHANS_PASSED_OVER =
            "{} orphaned-frame file(s) were not read: each is not a regular file — a link, a FIFO or a device — or " +
                "holds more than {} bytes, or git has tracked its name. Said once."
        private const val ORPHANS_UNANSWERED =
            "{} orphaned-frame file(s) were not read: git could not say what it has ever tracked under .ps ({}). " +
                "Said once."
        private const val ORPHANS_KNOWN =
            "{} orphaned-frame file(s) git has tracked were neither read nor counted: each may be what a pull " +
                "delivered rather than frames this server kept, and none is a gap in the history. Said once."
        private const val ORPHANS_KNOWN_KEY = "orphans known to git"
        private const val ORPHAN_NOT_A_FILE =
            "An orphaned frame was held in memory rather than written: where its lesson's orphans go is not a " +
                "regular file — a link, most likely — and an append-only file is never replaced. It is written " +
                "once a regular file or nothing stands there, and lost if the server stops first. Said once."
        private const val ORPHAN_NOT_A_FILE_KEY = "orphan not a file"
        private const val ORPHANS_KEY = "orphans: "
        private const val ORPHANS_PASSED_KEY = "orphans passed over"
        private const val ORPHANS_UNANSWERED_KEY = "orphans unanswered"
        private const val NOT_REPLAYED = "not replayed: "
        private const val LIMIT = "limit"
        private const val OVER_SETTLED_LIMIT =
            "Raw frames of runs set aside and of orphans, held in memory, reached {} characters; beyond that " +
                "they are dropped until .ps is usable again, so that the gradings in flight keep {} characters of " +
                "their own. Said once."
        private const val SETTLED_LIMIT_KEY = "settled limit"

        /** One part in this many of what the log holds is kept for the gradings in flight (#378). */
        private const val LIVE_SHARE = 4

        /** What the log holds in memory at most, across every session, while `.ps` is refused. */
        const val HELD_LIMIT = 8_000_000L

        /** The largest orphans file read to count its frames (#378). */
        const val ORPHAN_BYTES = 16L * 1024 * 1024

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
