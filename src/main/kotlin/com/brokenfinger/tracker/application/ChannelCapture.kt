package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.GradingFrameFacts
import org.slf4j.LoggerFactory

/**
 * The frame pipeline of **one** subscription — everything between a received frame and a
 * durable record ([[decisions/2026-08-05-capture-pipeline-stages]]).
 *
 * One channel outlives many gradings, so this is a state machine rather than a function: a
 * `start` frame opens a raw session and a [GradingSessionAssembler], the frames in between
 * feed it, and the terminal frame settles it into a record. Stage 1 comes first in the literal
 * sense — the append happens before any interpretation of the frame is attempted, because a
 * grading Programmers has already broadcast can never be fetched again (protocol doc §11).
 *
 * Everything after that append is allowed to fail: a write that throws costs one record and
 * never the subscription, since the frames are still on disk and more gradings will follow.
 *
 * What arrives here is an [ObservedFrame] — the wire text to append, plus the facts
 * `protocol/parse` read out of it. No message type reaches this far
 * ([[decisions/2026-08-05-protocol-dependency-direction]] decision 2).
 *
 * Not thread-safe. One instance belongs to one channel's collector, which delivers frames in
 * arrival order.
 */
class ChannelCapture(
    private val channel: ChannelKey,
    private val rawLog: RawSessionLog,
    private val registry: SubscriptionRegistry,
    private val writer: RecordWriter,
    private val timer: ProblemTimer,
) {
    private var live: LiveGrading? = null

    /** The preceding run's error text, waiting for the submit it may promote (design §3.3). */
    private var boundErrorText: String? = null

    suspend fun onFrame(frame: ObservedFrame) {
        val grading = gradingFor(frame) ?: return outsideGrading()
        rawLog.append(grading.rawSessionId, frame.rawText)
        assemble(grading, frame)
    }

    private suspend fun gradingFor(frame: ObservedFrame): LiveGrading? {
        val start = startOf(frame) ?: return live
        abandonInFlight()
        return opened(start)
    }

    private fun startOf(frame: ObservedFrame): GradingFrameFacts? = frame.facts?.takeIf { it.startsGrading }

    private fun opened(start: GradingFrameFacts): LiveGrading {
        val grading = LiveGrading(
            rawLog.start(channel.lessonId.value),
            GradingSessionAssembler.of(channel, bindingFor(start)),
        )
        live = grading
        bookkeeping(registry::markActive)
        return grading
    }

    /**
     * Hands the preceding run's error text to this grading and drops it in the same breath.
     * The binding is per channel and lasts exactly one grading (design §3.3): only a submit
     * can be promoted by one, and a run that follows a run supersedes rather than inherits it.
     */
    private fun bindingFor(start: GradingFrameFacts): String? {
        val bound = boundErrorText.takeIf { start.isSubmit() }
        boundErrorText = null
        return bound
    }

    private suspend fun assemble(grading: LiveGrading, frame: ObservedFrame) {
        val facts = frame.facts ?: return
        grading.assembler.accept(facts)
        if (!grading.assembler.hasTerminated()) return
        settle(grading, frame.rawText)
    }

    private suspend fun settle(grading: LiveGrading, terminalFrame: String?) {
        live = null
        bookkeeping(registry::markSettled)
        val session = grading.assembler.settle()
        rebind(session)
        record(grading.rawSessionId, session, terminalFrame)
    }

    /**
     * Only a run ever yields error text, and only the most recent one may promote the next
     * submit. A run that produced none clears the binding instead of leaving a stale one
     * behind — a compiler diagnostic from ten edits ago must promote nothing.
     */
    private fun rebind(session: GradingSession) {
        if (session.action != GradingAction.RUN) return
        boundErrorText = session.errorText
    }

    // Logged, never rethrown: the verdict is unrecoverable but this write is not — it is
    // retryable from the raw log — and the next grading on this channel still needs us.
    private suspend fun record(rawSessionId: RawSessionId, session: GradingSession, terminalFrame: String?) {
        runCatching { writer.write(captureOf(rawSessionId, session, terminalFrame)) }
            .onFailure { logger.error("Lesson {} settled but was not recorded; its frames are kept", lessonId(), it) }
    }

    private fun captureOf(rawSessionId: RawSessionId, session: GradingSession, terminalFrame: String?) = SettledCapture(
        session = session,
        rawSessionId = rawSessionId,
        lessonId = lessonId(),
        // The catalog title arrives on its own schedule and a placeholder here would read as
        // measured; the layout falls back to the lesson number on its own (design §5.1).
        title = "",
        language = channel.language,
        elapsedSec = timer.elapsedSecOf(lessonId()),
        terminalFrame = terminalFrame,
    )

    /**
     * A second start before a terminal frame means the first grading will never finish. It is
     * settled incomplete rather than having the newcomer's frames folded into it: a partial
     * record of one real grading is worth more than two gradings mixed into one.
     */
    private suspend fun abandonInFlight() {
        val abandoned = live ?: return
        logger.warn("A grading started on lesson {} before the previous one terminated", lessonId())
        settle(abandoned, terminalFrame = null)
    }

    /**
     * The connection dropped. Anything in flight will never receive a terminal frame, so it
     * settles INCOMPLETE with its frames kept rather than waiting for a result that is not
     * coming — the broadcast is never re-sent (protocol doc §11).
     */
    suspend fun connectionLost() {
        val abandoned = live ?: return
        logger.warn("Observation of lesson {} dropped mid-grading; settling it incomplete", lessonId())
        settle(abandoned, terminalFrame = null)
    }

    // Welcome, confirmation, and anything trailing a terminal frame. The raw log's unit is one
    // grading, so there is nowhere to append these and no verdict rides on them. The frame text
    // itself is never logged — a broadcast carries a learner's solving history (dev rules §7).
    private fun outsideGrading() {
        logger.debug("Dropped a frame belonging to no grading on lesson {}", lessonId())
    }

    // The registry is bookkeeping and a grading is not: a channel it has already forgotten
    // must not cost us the verdict currently arriving on it.
    private fun bookkeeping(change: (ChannelKey) -> Unit) {
        runCatching { change(channel) }
            .onFailure { logger.warn("Subscription bookkeeping is out of sync for lesson {}", lessonId()) }
    }

    private fun lessonId(): Long = channel.lessonId.value

    private companion object {
        val logger = LoggerFactory.getLogger(ChannelCapture::class.java)
    }
}

/** The grading in flight on a channel: where its frames are kept and what interprets them. */
private class LiveGrading(val rawSessionId: RawSessionId, val assembler: GradingSessionAssembler)
