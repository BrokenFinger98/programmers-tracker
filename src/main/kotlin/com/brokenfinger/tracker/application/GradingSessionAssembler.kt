package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.TerminalKind
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.TerminationRule
import com.brokenfinger.tracker.domain.calc.VerdictResolver
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.protocol.parse.GradingMessageMapper

/**
 * Accumulates the frames broadcast on one channel identifier and settles them into a
 * [GradingSession]. One instance observes one grading; it is not thread-safe and is not
 * reused across gradings.
 *
 * It assembles and delegates, nothing more (dev rules §3): which frame ends the stream is
 * [TerminationRule]'s answer and what the failure means is [VerdictResolver]'s, both pure.
 * What lives here is only the accumulation the calculators need as a snapshot.
 *
 * **It owns no clock.** Settling is the caller's decision — the 150 s timeout firing, the
 * ping watchdog declaring the socket dead, or a terminal frame arriving. [settle] before a
 * terminal frame yields [Outcome.INCOMPLETE] with every frame kept, because a grading
 * Programmers has already broadcast can never be fetched again (protocol doc §11).
 */
class GradingSessionAssembler private constructor(private val kind: ProblemKind, private val boundErrorText: String?) {
    private val frames = mutableListOf<SubmitMessage>()
    private val announcedIds = linkedSetOf<Long>()

    // A run announces a count rather than ids, so completeness is checked against whichever
    // of the two the stream actually promised.
    private var announcedCount: Int? = null
    private val testcases = linkedMapOf<Long, TestcaseResult>()
    private var action: GradingAction? = null
    private var terminal: TerminalKind? = null
    private var errorText: String? = null

    /** Records one frame. Frames arriving after termination are absorbed, not rejected. */
    fun accept(message: SubmitMessage) {
        frames += message
        action = action ?: GradingMessageMapper.actionOf(message)
        errorText = errorText ?: GradingMessageMapper.errorTextOf(message)
        announcedIds += GradingMessageMapper.announcedTestcaseIds(message)
        announcedCount = announcedCount ?: GradingMessageMapper.announcedTestcaseCount(message)
        GradingMessageMapper.testcaseOf(message)?.let { testcases[it.id] = it }
        markTerminal(message)
    }

    fun hasTerminated(): Boolean = terminal != null

    fun settle(): GradingSession {
        val graded = testcases.values.sortedBy { it.id }
        if (terminal == null) return sessionOf(Outcome.INCOMPLETE, null, graded)
        val verdict = VerdictResolver.resolve(graded, boundErrorText ?: errorText)
        return sessionOf(outcomeOf(verdict), verdict, graded)
    }

    /**
     * For an algorithm submit `result_lesson_challenge` arrives before `finish`; the late
     * `finish` belongs to this grading, not a new one (design §4.2). Keeping the first
     * terminal is what makes that absorption rather than a second session.
     */
    private fun markTerminal(message: SubmitMessage) {
        if (terminal != null) return
        val received = GradingMessageMapper.terminalKindOf(message) ?: return
        val observed = action ?: return
        if (!TerminationRule.isTerminal(received, observed, kind)) return
        terminal = received
    }

    // A terminal frame we cannot classify is UNKNOWN, never the nearest neighbour — the
    // memory-limit message has never been measured (protocol doc §14).
    private fun outcomeOf(verdict: Verdict?): Outcome {
        if (verdict == null) return Outcome.UNKNOWN
        return Outcome.JUDGED
    }

    /** Unverifiable is not the same as verified: with nothing promised, this stays false. */
    private fun isComplete(): Boolean {
        if (announcedIds.isNotEmpty()) return testcases.keys.containsAll(announcedIds)
        return announcedCount?.let { testcases.size >= it } ?: false
    }

    private fun sessionOf(outcome: Outcome, verdict: Verdict?, graded: List<TestcaseResult>) = GradingSession(
        kind = kind,
        action = action,
        outcome = outcome,
        verdict = verdict,
        testcases = graded,
        testcasesComplete = isComplete(),
        errorText = errorText,
        frames = frames.toList(),
    )

    companion object {
        /**
         * Opens an assembler for one channel. [boundErrorText] is the error text of an
         * already-bound preceding run, when the caller has one — binding it is that
         * caller's job (design §3.3), and an unbound session simply reports the weaker
         * verdict rather than guessing.
         */
        fun of(channel: ChannelIdentifier, boundErrorText: String? = null) = GradingSessionAssembler(
            GradingMessageMapper.problemKindOf(channel.type),
            boundErrorText,
        )
    }
}
