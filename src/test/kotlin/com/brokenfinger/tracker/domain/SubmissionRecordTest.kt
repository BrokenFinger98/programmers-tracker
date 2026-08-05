package com.brokenfinger.tracker.domain

import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the record's value objects — 0 mocks (dev rules §6.1).
 *
 * The rule under test throughout is design §5.2: for database problems `score`, `rating`
 * and per-testcase `runTime`/`memorySize` are structurally absent (protocol doc §6), so
 * they must be null and never zero. A zero silently enters averages.
 */
class SubmissionRecordTest {
    @Test
    fun `a summary counts what was actually observed`() {
        val summary = TestcaseSummary.of(
            testcases = listOf(aTestcaseResult(id = 1), aTestcaseResult(id = 2, passed = false)),
            complete = true,
        )

        summary shouldBe TestcaseSummary(total = 2, passed = 1, failed = 1, complete = true)
    }

    @Test
    fun `an unreported testcase counts as failed, never as passed`() {
        val summary = TestcaseSummary.of(listOf(aTestcaseResult(passed = null)), complete = false)

        summary.passed shouldBe 0
        summary.failed shouldBe 1
    }

    @Test
    fun `a partially observed grading is marked incomplete so it cannot pass for a full one`() {
        val summary = TestcaseSummary.of(listOf(aTestcaseResult()), complete = false)

        summary.complete shouldBe false
    }

    @Test
    fun `an empty grading summarises to zeroes rather than throwing`() {
        val summary = TestcaseSummary.of(emptyList(), complete = false)

        summary shouldBe TestcaseSummary(total = 0, passed = 0, failed = 0, complete = false)
    }

    @Test
    fun `a score is only formed when the judge reported both halves`() {
        Score.ofReceived("1.4", "100.0") shouldBe Score(user = "1.4", perfect = "100.0")
    }

    @Test
    fun `a database grading has no score at all, so it is null and not zero`() {
        Score.ofReceived(null, null).shouldBeNull()
        Score.ofReceived("1.4", null).shouldBeNull()
    }

    @Test
    fun `a rating knows whether it moved`() {
        RatingChange.of(old = 1371, new = 1372).changed shouldBe true
        RatingChange.of(old = 1372, new = 1372).changed shouldBe false
    }

    @Test
    fun `a database grading has no rating at all, so it is null and not zero`() {
        RatingChange.ofReceived(null, null).shouldBeNull()
        RatingChange.ofReceived(1371, null).shouldBeNull()
    }

    @Test
    fun `a verdict is meaningful only for a judged outcome`() {
        val incomplete = aSubmissionRecord(outcome = Outcome.INCOMPLETE, verdict = null)

        incomplete.isJudged() shouldBe false
        incomplete.verdict.shouldBeNull()
    }

    @Test
    fun `a judged record carries its verdict`() {
        val judged = aSubmissionRecord(outcome = Outcome.JUDGED, verdict = Verdict.TIMEOUT)

        judged.isJudged() shouldBe true
        judged.verdict shouldBe Verdict.TIMEOUT
    }

    @Test
    fun `a record whose code attachment has not landed yet says so`() {
        val pending = aSubmissionRecord(codePath = null, codePending = true)

        pending.isCodeAttached() shouldBe false
    }
}
