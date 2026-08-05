package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.TerminalKind
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.TerminationRule
import com.brokenfinger.tracker.domain.calc.VerdictResolver

/**
 * Accumulates the frames broadcast on one channel and settles them into a
 * [GradingSession]. One instance observes one grading; it is not thread-safe and is not
 * reused across gradings.
 *
 * It is fed [GradingFrameFacts], never messages: what a frame *is* on the wire stops at
 * `protocol/parse` ([[decisions/2026-08-05-protocol-dependency-direction]] decision 2), so a
 * renamed Programmers message cannot reach the verdict path through here.
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
    private val frames = mutableListOf<GradingFrameFacts>()
    private val announcedIds = linkedSetOf<Long>()

    // A run announces a count rather than ids, so completeness is checked against whichever
    // of the two the stream actually promised.
    private var announcedCount: Int? = null
    private val testcases = linkedMapOf<Long, TestcaseResult>()
    private var action: GradingAction? = null
    private var terminal: TerminalKind? = null
    private var errorText: String? = null

    /** Records one frame. Frames arriving after termination are absorbed, not rejected. */
    fun accept(facts: GradingFrameFacts) {
        frames += facts
        action = action ?: facts.action
        errorText = errorText ?: facts.errorText
        announcedIds += facts.announcedTestcaseIds
        announcedCount = announcedCount ?: facts.announcedTestcaseCount
        facts.testcase?.let { testcases[it.id] = it }
        markTerminal(facts)
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
    private fun markTerminal(facts: GradingFrameFacts) {
        if (terminal != null) return
        val received = facts.terminalKind ?: return
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
        fun of(channel: ChannelKey, boundErrorText: String? = null) =
            GradingSessionAssembler(channel.kind, boundErrorText)
    }
}
