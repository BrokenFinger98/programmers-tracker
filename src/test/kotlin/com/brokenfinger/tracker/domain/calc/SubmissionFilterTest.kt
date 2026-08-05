package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class SubmissionFilterTest {
    private val early = aSubmissionRecord(ts = OffsetDateTime.parse("2026-07-30T10:00:00+09:00"))
    private val late = aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-03T10:00:00+09:00"))
    private val unresolved = aSubmissionRecord(
        ts = OffsetDateTime.parse("2026-08-04T10:00:00+09:00"),
        outcome = Outcome.INCOMPLETE,
        verdict = null,
    )

    @Test
    fun `no argument is not a filter, so an empty query is the whole history`() {
        SubmissionFilter.matching(listOf(early, late), since = null, verdict = null)
            .shouldContainExactly(early, late)
    }

    @Test
    fun `keeps only what falls on or after the bound`() {
        val kept = SubmissionFilter.matching(
            listOf(early, late),
            since = Since.Day(LocalDate.of(2026, 8, 1)),
            verdict = null,
        )

        kept.shouldContainExactly(late)
    }

    @Test
    fun `keeps only the requested verdict`() {
        val wrong = aSubmissionRecord(verdict = Verdict.WRONG)

        SubmissionFilter.matching(listOf(early, wrong), since = null, verdict = Verdict.WRONG)
            .shouldContainExactly(wrong)
    }

    /** A grading whose verdict was never resolved matches no verdict — not even by accident. */
    @Test
    fun `a submission with no verdict matches no verdict filter`() {
        Verdict.entries.forEach { verdict ->
            SubmissionFilter.matching(listOf(unresolved), since = null, verdict = verdict).shouldBeEmpty()
        }
    }

    @Test
    fun `applies both bounds together`() {
        val kept = SubmissionFilter.matching(
            listOf(early, late, unresolved),
            since = Since.Day(LocalDate.of(2026, 8, 1)),
            verdict = Verdict.PASS,
        )

        kept.shouldContainExactly(late)
    }

    @Test
    fun `an empty history filters to nothing rather than failing`() {
        SubmissionFilter.matching(emptyList(), since = Since.Day(LocalDate.of(2026, 8, 1)), verdict = Verdict.PASS)
            .shouldBeEmpty()
    }

    @Test
    fun `selects one problem by its lesson id`() {
        val other = aSubmissionRecord(lessonId = 131528)

        SubmissionFilter.ofProblem(listOf(early, other), 131528).shouldContainExactly(other)
    }

    @Test
    fun `a lesson with nothing recorded selects nothing`() {
        SubmissionFilter.ofProblem(listOf(early, late), 999999).shouldBeEmpty()
    }

    @Test
    fun `preserves the order it was given`() {
        SubmissionFilter.matching(listOf(late, early), since = null, verdict = null)
            .map { it.ts } shouldBe listOf(late.ts, early.ts)
    }
}
