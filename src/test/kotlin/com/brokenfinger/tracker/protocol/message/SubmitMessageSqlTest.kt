package com.brokenfinger.tracker.protocol.message

import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aFinishMessage
import com.brokenfinger.tracker.support.fixtures.aResultMessage
import com.brokenfinger.tracker.support.fixtures.aStartMessage
import com.brokenfinger.tracker.support.fixtures.aTestcaseMessage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** SQL streams — snake_case field naming, no finish on submit (protocol doc §6, verification #7/#8). */
class SubmitMessageSqlTest {
    private val submitStream = FixtureLoader.messages("sql-pass.jsonl")
    private val runStream = FixtureLoader.messages("sql-run.jsonl")

    @Test
    fun `parses snake_case start carrying testcase ids`() {
        submitStream[0] shouldBe aStartMessage(
            testcaseIds = listOf(5438L),
            challengeableType = "database",
            challengeableId = 2778L,
        )
    }

    @Test
    fun `maps snake_case testcase_id without run metrics`() {
        submitStream[1] shouldBe aTestcaseMessage(
            testcaseId = 5438L,
            testcasesCount = null,
            msg = "통과",
            runTime = null,
            memorySize = null,
            challengeableType = "database",
            challengeableId = 2778L,
        )
    }

    @Test
    fun `parses sql result without scores or rating`() {
        submitStream[2] shouldBe aResultMessage(
            scores = null,
            challengeableId = 2778L,
            challengeableType = "database",
            language = null,
            isNewRating = null,
            oldUserRating = null,
            newUserRating = null,
            finishModalLink = "/learn/courses/30/lessons/0",
            finishModalBtnText = "다음 문제 풀기",
            surveyUrl = null,
        )
    }

    // SQL never sends finish — waiting for it hangs forever (protocol doc §6).
    @Test
    fun `sql submit stream ends at result_lesson_challenge without finish`() {
        submitStream.none { it is SubmitMessage.Finish } shouldBe true
        submitStream.last().shouldBeInstanceOf<SubmitMessage.ResultLessonChallenge>()
    }

    @Test
    fun `parses run start without msg`() {
        runStream[0] shouldBe aStartMessage(
            action = "run",
            msg = null,
            testcaseIds = listOf(5437L),
            challengeableType = "database",
            challengeableId = 2778L,
        )
    }

    // returned_rows is a double-encoded JSON string; msg is explicitly null (protocol doc §6).
    @Test
    fun `parses run finish with double-encoded returned_rows and null msg`() {
        runStream[1] shouldBe aFinishMessage(
            action = "run",
            testcaseId = 5437L,
            passed = true,
            returnedRows = """{"columns":["USERS"],"data":[[4]]}""",
            challengeableType = "database",
            challengeableId = 2778L,
        )
    }
}
