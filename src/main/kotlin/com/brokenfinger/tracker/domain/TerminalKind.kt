package com.brokenfinger.tracker.domain

/**
 * Frames that are capable of ending a grading stream. Which one actually ends a given
 * stream depends on the (action × kind) cell — see
 * [com.brokenfinger.tracker.domain.calc.TerminationRule].
 */
enum class TerminalKind {
    FINISH,
    RESULT,
    RESULT_LESSON_CHALLENGE,
    ERROR,
}
