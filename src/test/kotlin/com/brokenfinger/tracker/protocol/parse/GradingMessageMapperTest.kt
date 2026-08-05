package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.TerminalKind
import com.brokenfinger.tracker.protocol.ChallengeableType
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aRunErrorText
import com.brokenfinger.tracker.support.fixtures.aTestcaseMessage
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * Protocol → domain mapping. This is the only place allowed to know that a testcase id is
 * spelled `testcaseId` on algorithm streams and `testcase_id` on database ones
 * (dev rules §2.1); everything above it sees [com.brokenfinger.tracker.domain.TestcaseResult].
 */
class GradingMessageMapperTest {
    private val algorithmSubmit = FixtureLoader.messages("algorithm-pass.jsonl")
    private val sqlSubmit = FixtureLoader.messages("sql-pass.jsonl")
    private val sqlRun = FixtureLoader.messages("sql-run.jsonl")
    private val algorithmRunError = FixtureLoader.messages("algorithm-run-error.jsonl")

    @Test
    fun `maps the two channel types onto the two problem kinds`() {
        GradingMessageMapper.problemKindOf(ChallengeableType.ALGORITHM) shouldBe ProblemKind.ALGORITHM
        GradingMessageMapper.problemKindOf(ChallengeableType.DATABASE) shouldBe ProblemKind.DATABASE
    }

    @Test
    fun `reads the grading action off measured submit and run frames`() {
        GradingMessageMapper.actionOf(algorithmSubmit[0]) shouldBe GradingAction.SUBMIT
        GradingMessageMapper.actionOf(sqlRun[0]) shouldBe GradingAction.RUN
    }

    @Test
    fun `an unknown frame carries no action rather than a guessed one`() {
        GradingMessageMapper.actionOf(anUnmeasuredFrame("paused")).shouldBeNull()
    }

    @Test
    fun `maps each measured terminal frame to its kind`() {
        GradingMessageMapper.terminalKindOf(algorithmSubmit[5]) shouldBe TerminalKind.FINISH
        GradingMessageMapper.terminalKindOf(algorithmSubmit[4]) shouldBe TerminalKind.RESULT_LESSON_CHALLENGE
        GradingMessageMapper.terminalKindOf(algorithmRunError[1]) shouldBe TerminalKind.ERROR
    }

    @Test
    fun `start test_group and testcase frames are never terminal`() {
        GradingMessageMapper.terminalKindOf(algorithmSubmit[0]).shouldBeNull()
        GradingMessageMapper.terminalKindOf(algorithmSubmit[1]).shouldBeNull()
        GradingMessageMapper.terminalKindOf(algorithmSubmit[2]).shouldBeNull()
    }

    // `result` is in the bundle's type catalog (protocol doc §8) and is the algorithm-run
    // terminal, but it has never been captured — so it has no fixture and the message parser
    // keeps it as Unknown. Recognising it by name is what lets that cell terminate at all.
    @Test
    fun `the uncaptured result frame is still recognised as a terminal kind`() {
        GradingMessageMapper.terminalKindOf(anUnmeasuredFrame("result")) shouldBe TerminalKind.RESULT
    }

    @Test
    fun `an unknown frame of any other type is not terminal`() {
        GradingMessageMapper.terminalKindOf(anUnmeasuredFrame("notice")).shouldBeNull()
    }

    @Test
    fun `camelCase and snake_case testcases land on the same domain type`() {
        GradingMessageMapper.testcaseOf(algorithmSubmit[2]) shouldBe aTestcaseResult(id = 154893)
        GradingMessageMapper.testcaseOf(sqlSubmit[1]) shouldBe
            aTestcaseResult(id = 5438, msg = "통과", runTime = null, memorySize = null)
    }

    // The database run path reports its single result on the finish frame itself
    // (protocol doc §6); an algorithm finish carries no result at all.
    @Test
    fun `the sql run finish frame is itself a testcase result`() {
        GradingMessageMapper.testcaseOf(sqlRun[1]) shouldBe
            aTestcaseResult(id = 5437, msg = null, runTime = null, memorySize = null)
    }

    @Test
    fun `the algorithm finish frame carries no testcase`() {
        GradingMessageMapper.testcaseOf(algorithmSubmit[5]).shouldBeNull()
    }

    // Identifier extraction must never fall back to a default (constitution). Declining to
    // map keeps the frame itself intact for reinterpretation.
    @Test
    fun `a testcase without an id is declined rather than defaulted`() {
        GradingMessageMapper.testcaseOf(aTestcaseMessage(testcaseId = null)).shouldBeNull()
    }

    @Test
    fun `expected testcase ids come from test_group on algorithm streams`() {
        GradingMessageMapper.announcedTestcaseIds(algorithmSubmit[1]) shouldContainExactly listOf(154893L, 154894L)
    }

    @Test
    fun `expected testcase ids come from start on database streams`() {
        GradingMessageMapper.announcedTestcaseIds(sqlSubmit[0]) shouldContainExactly listOf(5438L)
        GradingMessageMapper.announcedTestcaseIds(sqlRun[0]) shouldContainExactly listOf(5437L)
    }

    @Test
    fun `frames that announce nothing yield no expected ids`() {
        GradingMessageMapper.announcedTestcaseIds(algorithmSubmit[2]).shouldContainExactly(emptyList())
    }

    @Test
    fun `error text is unescaped at the parse boundary`() {
        val text = checkNotNull(GradingMessageMapper.errorTextOf(algorithmRunError[1]))

        text shouldBe aRunErrorText(0)
        text shouldContain "1 error"
    }

    @Test
    fun `frames other than error carry no error text`() {
        GradingMessageMapper.errorTextOf(algorithmSubmit[2]).shouldBeNull()
    }

    private fun anUnmeasuredFrame(type: String): SubmitMessage.Unknown {
        val parsed = SubmitMessage.ofReceived(anUnmeasuredJson(type))
        return parsed as SubmitMessage.Unknown
    }

    private fun anUnmeasuredJson(type: String): JsonObject = buildJsonObject {
        put("action", "run")
        put("type", type)
    }
}
