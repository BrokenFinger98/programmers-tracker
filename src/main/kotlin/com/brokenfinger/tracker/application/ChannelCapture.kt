package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.domain.SubmissionRecord
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
 * The same holds one step further along — a record that lands is handed to [RecordAttachment]
 * for its code, and a failure there costs files a later pass can write again.
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
    private val attachment: RecordAttachment,
    private val catalog: ProblemCatalog,
) {
    private var live: LiveGrading? = null

    /** The preceding run's error text, waiting for the submit it may promote (design §3.3). */
    private var boundErrorText: String? = null

    /** Grading frames seen with no grading open — counted so the log says how bad it is (#107). */
    private var orphans = 0

    suspend fun onFrame(frame: ObservedFrame) {
        val grading = gradingFor(frame) ?: return outsideGrading(frame)
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
        // Kept before the accept, and only for frames that yielded facts: the replay path
        // skips the others too, and the two must agree or every reconciliation looks new.
        grading.frames += frame.rawText
        grading.assembler.accept(facts)
        if (!grading.assembler.hasTerminated()) return
        settle(grading)
    }

    private suspend fun settle(grading: LiveGrading) {
        live = null
        bookkeeping(registry::markSettled)
        val session = grading.assembler.settle()
        rebind(session)
        record(grading.rawSessionId, session, grading.frames.toList())
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
    private suspend fun record(rawSessionId: RawSessionId, session: GradingSession, frames: List<String>) {
        val written = runCatching { writer.write(captureOf(rawSessionId, session, frames)) }
            .onFailure { logger.error("Lesson {} settled but was not recorded; its frames are kept", lessonId(), it) }
            .getOrNull() ?: return
        attach(written)
    }

    /**
     * Stage 3, once the verdict is durable. A failure here costs files that can be written
     * again from a page that still holds the code (protocol doc §10), so it is absorbed and
     * left to the startup retry pass — never allowed back onto the capture path.
     */
    private suspend fun attach(record: SubmissionRecord) {
        runCatching { attachment.attach(record) }
            .onFailure { logger.warn("Lesson {} was recorded but its code was not attached", lessonId(), it) }
    }

    private fun captureOf(rawSessionId: RawSessionId, session: GradingSession, frames: List<String>) = SettledCapture(
        session = session,
        rawSessionId = rawSessionId,
        lessonId = lessonId(),
        problem = catalog.find(channel.lessonId),
        language = channel.language,
        elapsedSec = timer.elapsedSecOf(lessonId()),
        observation = timer.observationOf(lessonId()),
        frames = frames,
    )

    /**
     * A second start before a terminal frame means the first grading will never finish. It is
     * settled incomplete rather than having the newcomer's frames folded into it: a partial
     * record of one real grading is worth more than two gradings mixed into one.
     */
    private suspend fun abandonInFlight() {
        val abandoned = live ?: return
        logger.warn("A grading started on lesson {} before the previous one terminated", lessonId())
        settle(abandoned)
    }

    /**
     * The connection dropped. Anything in flight will never receive a terminal frame, so it
     * settles INCOMPLETE with its frames kept rather than waiting for a result that is not
     * coming — the broadcast is never re-sent (protocol doc §11).
     */
    suspend fun connectionLost() {
        val abandoned = live ?: return
        logger.warn("Observation of lesson {} dropped mid-grading; settling it incomplete", lessonId())
        settle(abandoned)
    }

    /**
     * Two very different frames arrive here and used to be treated alike (#107).
     *
     * A frame carrying no facts is protocol noise — welcome, the subscription confirmation,
     * anything trailing a terminal frame. Nothing rides on it and there is nowhere to put
     * it; DEBUG is right.
     *
     * A frame that *does* carry facts is a **grading frame whose `start` we missed**: a
     * reconnect landed mid-grading, or the sensor announced the problem after Submit was
     * already pressed. It can never become a record — the `start` is what carries the action
     * and the identity — but dropping it silently is how a change in Programmers' framing
     * would stay invisible, which is the case dev rules §2.3 exists for. It is kept, and
     * said out loud.
     *
     * The frame text is never logged either way: a broadcast carries a learner's solving
     * history (dev rules §7).
     */
    private fun outsideGrading(frame: ObservedFrame) {
        val facts =
            frame.facts ?: return logger.debug("Dropped a frame belonging to no grading on lesson {}", lessonId())
        orphans += 1
        runCatching { rawLog.orphaned(channel.lessonId.value, frame.rawText) }
            .onFailure { logger.warn("Lesson {}: an orphaned frame could not be kept", lessonId(), it) }
        logger.warn(
            "Lesson {}: a grading frame (action={}) belonged to no grading — its start was missed, " +
                "so no record can be derived; {} so far on this channel, kept under the raw directory",
            lessonId(),
            facts.action,
            orphans,
        )
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
private class LiveGrading(val rawSessionId: RawSessionId, val assembler: GradingSessionAssembler) {
    /** Accepted frames, verbatim and in order — what the capture key is derived from (#149). */
    val frames = mutableListOf<String>()
}
