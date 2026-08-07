package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.TestcaseSummary
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit test for the commit subject (design §4.6). No files, no git, no clock — the subject
 * is a pure function of one record.
 *
 * Half of these are degradation cases on purpose: the catalog is not wired yet, so **every
 * record written today carries an empty title and no level**, and what `git log` shows in
 * that state is the normal case rather than an edge one.
 */
class CommitMessageTest {
    @Test
    fun `a wrong answer reads as the design's example`() {
        val record = aSubmissionRecord(
            level = 2,
            title = "소수 찾기",
            attempt = 3,
            verdict = Verdict.WRONG,
            tcSummary = TestcaseSummary(total = 16, passed = 12, failed = 4, complete = true),
        )

        CommitMessage.of(record) shouldBe "[Lv2] 소수 찾기 — WRONG (12/16, attempt 3)"
    }

    @Test
    fun `a pass also carries how long the problem took`() {
        val record = aSubmissionRecord(
            level = 2,
            title = "소수 찾기",
            attempt = 4,
            elapsedSec = 1458,
            verdict = Verdict.PASS,
            tcSummary = TestcaseSummary(total = 16, passed = 16, failed = 0, complete = true),
        )

        CommitMessage.of(record) shouldBe "[Lv2] 소수 찾기 — PASS (16/16, attempt 4, 24m18s)"
    }

    @Test
    fun `an hour of solving stays readable`() {
        val record = aSubmissionRecord(elapsedSec = 3782, verdict = Verdict.PASS)

        CommitMessage.of(record) shouldBe "[Lv0] 두 수의 곱 구하기 — PASS (1/1, attempt 2, 1h03m02s)"
    }

    @Test
    fun `an unknown level drops the bracket rather than inventing a zero`() {
        val record = aSubmissionRecord(level = null, verdict = Verdict.WRONG)

        CommitMessage.of(record) shouldBe "두 수의 곱 구하기 — WRONG (1/1, attempt 2)"
    }

    @Test
    fun `a record with no title falls back to the lesson id — the state of every record today`() {
        val record = aSubmissionRecord(level = null, title = "", verdict = Verdict.WRONG)

        CommitMessage.of(record) shouldBe "120804 — WRONG (1/1, attempt 2)"
    }

    @Test
    fun `a blank title is no title either`() {
        val record = aSubmissionRecord(title = "   ", verdict = Verdict.WRONG)

        CommitMessage.of(record) shouldBe "[Lv0] 120804 — WRONG (1/1, attempt 2)"
    }

    @Test
    fun `an unjudged submit reports its outcome instead of borrowing a verdict`() {
        val record = aSubmissionRecord(outcome = Outcome.INCOMPLETE, verdict = null)

        CommitMessage.of(record) shouldBe "[Lv0] 두 수의 곱 구하기 — INCOMPLETE (1/1, attempt 2)"
    }

    /**
     * The screen shows a cached 100.0 while the record says UNKNOWN (#74); the subject is
     * where a user first meets that contradiction, so the measured reason rides along.
     */
    @Test
    fun `a cached-result unknown names its reason in the subject`() {
        val record = aSubmissionRecord(
            outcome = Outcome.UNKNOWN,
            verdict = null,
            tcSummary = TestcaseSummary.of(emptyList(), complete = false),
            testcases = emptyList(),
            errorText = "같은 코드로 채점한 결과가 있습니다.",
        )

        CommitMessage.of(record) shouldBe "[Lv0] 두 수의 곱 구하기 — UNKNOWN (cached result, attempt 2)"
    }

    /** An unmeasured error text explains nothing — a wrong reason printed confidently is worse. */
    @Test
    fun `an unexplained unknown stays a plain UNKNOWN`() {
        val record = aSubmissionRecord(
            outcome = Outcome.UNKNOWN,
            verdict = null,
            tcSummary = TestcaseSummary.of(emptyList(), complete = false),
            testcases = emptyList(),
            errorText = "서버 점검 중입니다.",
        )

        CommitMessage.of(record) shouldBe "[Lv0] 두 수의 곱 구하기 — UNKNOWN (attempt 2)"
    }

    @Test
    fun `a grading that reported no testcase carries no counts`() {
        val record = aSubmissionRecord(
            verdict = Verdict.COMPILE_ERROR,
            testcases = emptyList(),
            tcSummary = TestcaseSummary(total = 0, passed = 0, failed = 0, complete = true),
        )

        CommitMessage.of(record) shouldBe "[Lv0] 두 수의 곱 구하기 — COMPILE_ERROR (attempt 2)"
    }

    @Test
    fun `a partially observed grading says so rather than passing for a full one`() {
        val record = aSubmissionRecord(
            verdict = Verdict.WRONG,
            tcSummary = TestcaseSummary(total = 16, passed = 12, failed = 4, complete = false),
        )

        CommitMessage.of(record) shouldBe "[Lv0] 두 수의 곱 구하기 — WRONG (12/16 partial, attempt 2)"
    }

    @Test
    fun `an elapsed time we never measured is left out of a pass`() {
        val record = aSubmissionRecord(elapsedSec = -1, verdict = Verdict.PASS)

        CommitMessage.of(record) shouldBe "[Lv0] 두 수의 곱 구하기 — PASS (1/1, attempt 2)"
    }
}
