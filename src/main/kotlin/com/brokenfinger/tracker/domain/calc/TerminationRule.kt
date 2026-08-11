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
     * An error frame ends a grading **except on the algorithm run path**, where measurement
     * says something follows it.
     *
     * It ends a submit: an identical resubmission returns a cached result and then errors
     * within a second (protocol doc §13.2), and nothing comes after.
     *
     * A run is different and was wrong here until #152. The run path emits **one error frame
     * per diagnostic** — `index: 0`, `index: 1` — and then a `result` (§7), which is what the
     * matrix above already said. Stopping at the first error cut a two-diagnostic compile
     * failure in half: the remaining frames arrived to a closed grading and were filed as
     * orphans whose "start was missed", which is the one thing that had not happened.
     * Measured 2026-08-11 on lesson 181946.
     *
     * Every other cell keeps the short circuit, because no measurement contradicts it — a
     * failing database run has never been captured.
     */
    fun isTerminal(received: TerminalKind, action: GradingAction, kind: ProblemKind): Boolean {
        if (received == TerminalKind.ERROR) return errorEnds(action, kind)
        return received == terminalFor(action, kind)
    }

    private fun errorEnds(action: GradingAction, kind: ProblemKind): Boolean =
        !(action == GradingAction.RUN && kind == ProblemKind.ALGORITHM)
}
