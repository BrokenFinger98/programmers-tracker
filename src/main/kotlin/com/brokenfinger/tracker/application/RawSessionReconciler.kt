package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.protocol.parse.StoredChannel
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Startup recovery for the durable queue — stage 2 re-run over the sessions a crash left
 * behind ([[decisions/2026-08-05-capture-pipeline-stages]]).
 *
 * The raw log **is** the queue: whatever `.ps/raw` still lists at startup is a grading whose
 * frames were made durable but whose record never landed. Reading those bytes back and
 * settling them the way the live path would is the whole point of putting the commit point at
 * frame arrival — a grading Programmers has already broadcast can never be fetched again
 * (protocol doc §11), so a process that dies mid-grading must cost a restart, not a verdict.
 *
 * Two properties make it safe to run unconditionally on every boot:
 *
 * - **Duplicate-free by delegation.** The capture key is a digest of the terminal frame
 *   (design §5.2), so replaying the same stored bytes derives the same key and
 *   [RecordWriter] drops it. No second mechanism is invented here; inventing one would give
 *   the two paths two different notions of "already recorded".
 * - **Per-session isolation.** One unreadable file, one stream that announced no action, one
 *   identifier nothing could resolve — each costs its own session and nothing behind it.
 *
 * What it deliberately does *not* recover is the run-to-submit error binding (design §3.3):
 * that lived in memory and died with the process, so a reconciled submit reports the weaker
 * verdict rather than a promoted guess.
 */
class RawSessionReconciler(
    private val rawLog: RawSessionLog,
    private val writer: RecordWriter,
    private val timer: ProblemTimer,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Reconciles every orphaned session, oldest first. An empty work list is a no-op. */
    suspend fun reconcile(): ReconcileReport {
        val orphaned = rawLog.unprocessed()
        if (orphaned.isEmpty()) return ReconcileReport()
        logger.info("Reconciling {} raw session(s) left behind by an earlier run", orphaned.size)
        return orphaned.fold(ReconcileReport()) { report, session -> report.and(outcomeOf(session)) }
    }

    // Nothing rethrows: the frames stay on disk either way, so a session we cannot settle today
    // is one we may settle after a fix — but only if the ones behind it were not abandoned too.
    private suspend fun outcomeOf(session: RawSession): ReconcileReport =
        runCatching { reconciled(session) }.getOrElse { failed(session, it) }

    private suspend fun reconciled(session: RawSession): ReconcileReport {
        val lines = linesOf(session)
        val channel = channelOf(lines) ?: error("no stored frame named a channel we could resolve")
        val replay = replayed(channel, lines)
        return recorded(captureOf(session, channel, replay), replay.skipped)
    }

    private suspend fun recorded(capture: SettledCapture, skipped: Int): ReconcileReport {
        val written = writer.write(capture)
        if (written == null) return ReconcileReport(duplicates = 1, skippedLines = skipped)
        return ReconcileReport(recorded = 1, skippedLines = skipped)
    }

    private fun replayed(channel: ChannelIdentifier, lines: List<String>): SessionReplay {
        val replay = SessionReplay(channel)
        // Stops at the terminal frame exactly as the live path does: what follows one belongs
        // to no grading, and folding it in would make a replay differ from the capture.
        lines.takeWhile { !replay.hasTerminated() }.forEach { replay.feed(it) }
        return replay
    }

    private fun captureOf(session: RawSession, channel: ChannelIdentifier, replay: SessionReplay) = SettledCapture(
        session = replay.settle(),
        rawSessionId = session.id,
        lessonId = session.lessonId,
        // The catalog title arrives on its own schedule; the layout falls back to the lesson
        // number on its own (design §5.1).
        title = "",
        language = channel.language,
        elapsedSec = elapsedOf(session),
        terminalFrame = replay.terminalFrame,
    )

    /**
     * Time on the problem as of the moment this grading started, not as of the restart. The
     * timer counted straight through the outage, so filing what it now reports would credit a
     * night of downtime to the learner — a measured verdict next to an invented duration.
     */
    private fun elapsedOf(session: RawSession): Long =
        (timer.elapsedSecOf(session.lessonId) - waitedSince(session.startedAt)).coerceAtLeast(0)

    private fun waitedSince(startedAt: Instant): Long =
        Duration.between(startedAt, clock.instant()).seconds.coerceAtLeast(0)

    /** The channel is stored only in the ActionCable envelope — see [StoredChannel] for why. */
    private fun channelOf(lines: List<String>): ChannelIdentifier? = lines.asSequence()
        .mapNotNull { broadcastOf(it)?.identifier }
        .mapNotNull { StoredChannel.ofReceived(it) }
        .firstOrNull()

    /**
     * Decoded with replacement rather than reported: a crash can tear a line in the middle of
     * a multi-byte character, and one bad byte must not cost the whole session.
     */
    private fun linesOf(session: RawSession): List<String> =
        String(Files.readAllBytes(session.path), StandardCharsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

    private fun failed(session: RawSession, cause: Throwable): ReconcileReport {
        logger.error("Lesson {} could not be reconciled; its frames are kept", session.lessonId, cause)
        return ReconcileReport(failed = 1)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(RawSessionReconciler::class.java)
    }
}

/** What one reconciliation pass did. Every stored session lands in exactly one count. */
data class ReconcileReport(
    val recorded: Int = 0,
    /** Sessions an earlier pass had already recorded — dropped by the writer's capture key. */
    val duplicates: Int = 0,
    val failed: Int = 0,
    /** Lines no frame could be read from, kept on disk and counted rather than dropped. */
    val skippedLines: Int = 0,
) {
    fun and(other: ReconcileReport) = ReconcileReport(
        recorded + other.recorded,
        duplicates + other.duplicates,
        failed + other.failed,
        skippedLines + other.skippedLines,
    )
}

/**
 * One stored session being replayed through the same assembler a live capture uses, so the
 * verdict a reconciled record carries is the verdict the socket loop would have produced.
 */
private class SessionReplay(channel: ChannelIdentifier) {
    private val assembler = GradingSessionAssembler.of(channel)

    /** The stored line that ended the stream — the basis the capture key is derived from. */
    var terminalFrame: String? = null
        private set

    var skipped = 0
        private set

    fun feed(line: String) {
        val broadcast = broadcastOf(line) ?: return skip(line)
        assembler.accept(SubmitMessage.ofReceived(broadcast.message))
        if (hasTerminated()) terminalFrame = terminalFrame ?: line
    }

    fun hasTerminated(): Boolean = assembler.hasTerminated()

    fun settle(): GradingSession = assembler.settle()

    // The line itself is never logged — a stored frame carries a learner's solving history
    // (dev rules §7). A crash mid-append is the expected cause, so this is noise, not alarm.
    private fun skip(line: String) {
        skipped++
        SessionReplay.logger.warn("A stored line yielded no frame and was kept unread (length={})", line.length)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SessionReplay::class.java)
    }
}

private fun broadcastOf(line: String): ActionCableFrame.Broadcast? =
    ActionCableFrame.ofReceived(line) as? ActionCableFrame.Broadcast
