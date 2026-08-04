package com.brokenfinger.tracker.protocol.message

import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aFinishMessage
import com.brokenfinger.tracker.support.fixtures.aResultMessage
import com.brokenfinger.tracker.support.fixtures.aScore
import com.brokenfinger.tracker.support.fixtures.aStartMessage
import com.brokenfinger.tracker.support.fixtures.aTestGroupMessage
import com.brokenfinger.tracker.support.fixtures.aTestcaseMessage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Algorithm submit stream — camelCase field naming (protocol doc §5, verification #6/#1). */
class SubmitMessageAlgorithmTest {
    private val passStream = FixtureLoader.messages("algorithm-pass.jsonl")
    private val wrongStream = FixtureLoader.messages("algorithm-wrong.jsonl")

    @Test
    fun `parses submit start`() {
        passStream[0] shouldBe aStartMessage()
    }

    @Test
    fun `parses test_group with camelCase testcase ids`() {
        passStream[1] shouldBe aTestGroupMessage()
    }

    @Test
    fun `parses passing testcase with run metrics`() {
        passStream[2] shouldBe aTestcaseMessage()
    }

    @Test
    fun `parses result_lesson_challenge with scores and rating`() {
        passStream[4] shouldBe aResultMessage()
    }

    @Test
    fun `parses bare finish`() {
        passStream[5] shouldBe aFinishMessage()
    }

    @Test
    fun `parses failing testcase from wrong-answer stream`() {
        wrongStream[3] shouldBe aTestcaseMessage(
            testcaseId = 154802L,
            passed = false,
            msg = "실패 (0.01ms, 75.3MB)",
            memorySize = 78950400L,
        )
    }

    // Measured: 1 of 16 passed → partial score "1.4" (protocol doc §5).
    @Test
    fun `keeps partial score on wrong-answer result`() {
        wrongStream[4] shouldBe aResultMessage(
            passed = false,
            scores = listOf(aScore(score = "1.4")),
            userScore = "1.4",
            challengeableId = 14642L,
            isNewRating = null,
            oldUserRating = null,
            newUserRating = null,
        )
    }
}
