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
     * An error frame ends every cell: an identical resubmission returns a cached result and
     * then errors within a second (protocol doc §13.2), and a failed run ends the stream
     * there (§7). Treating it as non-terminal is what makes "record the error too"
     * unreachable.
     */
    fun isTerminal(received: TerminalKind, action: GradingAction, kind: ProblemKind): Boolean {
        if (received == TerminalKind.ERROR) return true
        return received == terminalFor(action, kind)
    }
}
