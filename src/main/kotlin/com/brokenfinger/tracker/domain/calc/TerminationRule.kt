package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.TerminalKind

/**
 * Which frame ends a grading stream.
 *
 * Termination is an (action × kind) matrix rather than a single condition
 * (protocol doc §5–§7). A database submit never receives a finish frame, so waiting for
 * one hangs forever — while a database run does receive one. Pure: no I/O, no protocol
 * types (development-rules §3).
 */
object TerminationRule {
    private val terminals = mapOf(
        (GradingAction.SUBMIT to ProblemKind.ALGORITHM) to TerminalKind.FINISH,
        (GradingAction.RUN to ProblemKind.ALGORITHM) to TerminalKind.RESULT,
        (GradingAction.SUBMIT to ProblemKind.DATABASE) to TerminalKind.RESULT_LESSON_CHALLENGE,
        (GradingAction.RUN to ProblemKind.DATABASE) to TerminalKind.FINISH,
    )

    fun terminalFor(action: GradingAction, kind: ProblemKind): TerminalKind = terminals.getValue(action to kind)

    /**
     * **An error frame ends nothing.** The matrix above is the only rule, and it always was.
     *
     * This used to short-circuit on `TerminalKind.ERROR`, inferred from protocol doc §13.2 —
     * "an identical resubmission returns a cached result and then errors within a second" —
     * read as "the error is the end". Measurement contradicted that reading on both paths it
     * was applied to, four days apart:
     *
     * - **A failing run** sends one error frame per diagnostic and then a `result` (§7).
     *   Stopping at the first error split a two-diagnostic compile failure in half (#152).
     * - **A cached-result submit** sends its error and then the whole grading anyway —
     *   measured on lesson 120802: `start · error · test_group · testcase ×18 ·
     *   result_lesson_challenge · finish`. Stopping at the error discarded eighteen passing
     *   testcases and recorded UNKNOWN (#154).
     *
     * Both times the frame the matrix already named was sitting at the end of the stream.
     *
     * The cost is stated in the ADR: a stream that genuinely does end at an error now stays
     * open until the silence deadline and lands INCOMPLETE. That is the safe direction —
     * INCOMPLETE says what happened, where the old behaviour said UNKNOWN and threw the rest
     * away.
     */
    fun isTerminal(received: TerminalKind, action: GradingAction, kind: ProblemKind): Boolean =
        received == terminalFor(action, kind)
}
