package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.TerminalKind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Termination differs per (action × kind), not per kind — measured in protocol doc §5–§7.
 * Waiting for `finish` on an SQL submit hangs forever; so does waiting for it on an
 * algorithm run.
 */
class TerminationRuleTest {
    @Test
    fun `algorithm submit terminates on finish`() {
        TerminationRule.terminalFor(GradingAction.SUBMIT, ProblemKind.ALGORITHM) shouldBe TerminalKind.FINISH
    }

    @Test
    fun `algorithm run terminates on result`() {
        TerminationRule.terminalFor(GradingAction.RUN, ProblemKind.ALGORITHM) shouldBe TerminalKind.RESULT
    }

    @Test
    fun `sql submit terminates on result_lesson_challenge because finish never arrives`() {
        TerminationRule.terminalFor(GradingAction.SUBMIT, ProblemKind.DATABASE) shouldBe
            TerminalKind.RESULT_LESSON_CHALLENGE
    }

    @Test
    fun `sql run does terminate on finish`() {
        TerminationRule.terminalFor(GradingAction.RUN, ProblemKind.DATABASE) shouldBe TerminalKind.FINISH
    }

    @Test
    fun `error terminates every cell`() {
        val cells = GradingAction.entries.flatMap { action -> ProblemKind.entries.map { action to it } }

        cells.forEach { (action, kind) ->
            TerminationRule.isTerminal(TerminalKind.ERROR, action, kind) shouldBe true
        }
    }

    @Test
    fun `a result_lesson_challenge does not terminate an algorithm submit`() {
        TerminationRule.isTerminal(
            TerminalKind.RESULT_LESSON_CHALLENGE,
            GradingAction.SUBMIT,
            ProblemKind.ALGORITHM,
        ) shouldBe false
    }

    @Test
    fun `action parsing is lenient — an unknown action yields null rather than throwing`() {
        GradingAction.ofReceived("submit") shouldBe GradingAction.SUBMIT
        GradingAction.ofReceived("run") shouldBe GradingAction.RUN
        GradingAction.ofReceived("save").shouldBeNull()
        GradingAction.ofReceived(null).shouldBeNull()
    }
}
