package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The gap between attempts is the difference between five submits in ninety seconds and five
 * across three evenings — guessing versus thinking. Nothing in a record could tell them apart
 * until #207, because `sincePrevSec` was declared and never set.
 */
class SubmissionGapsTest {
    private val start = OffsetDateTime.parse("2026-08-12T09:00:00+09:00")

    @Test
    fun `the first grading of a problem has no previous one`() {
        SubmissionGaps.from(emptyList()).sincePrevious(120802, start).shouldBeNull()
    }

    @Test
    fun `the second reports the seconds between them`() {
        val gaps = SubmissionGaps.from(emptyList())

        gaps.sincePrevious(120802, start)

        gaps.sincePrevious(120802, start.plusSeconds(312)) shouldBe 312
    }

    /** Measured from the previous grading, not from the first — a gap, not a total. */
    @Test
    fun `each gap is measured from the one before it`() {
        val gaps = SubmissionGaps.from(emptyList())

        gaps.sincePrevious(120802, start)
        gaps.sincePrevious(120802, start.plusSeconds(100))

        gaps.sincePrevious(120802, start.plusSeconds(130)) shouldBe 30
    }

    @Test
    fun `problems are counted apart`() {
        val gaps = SubmissionGaps.from(emptyList())

        gaps.sincePrevious(120802, start)

        gaps.sincePrevious(120803, start.plusSeconds(50)).shouldBeNull()
    }

    /**
     * Language is deliberately **not** part of the key, unlike the scheduling rule in #174: that
     * asks whether a pass demonstrates a language, this asks how long since the learner last
     * touched the problem, and switching language is still touching it.
     */
    @Test
    fun `switching language still counts as touching the problem`() {
        val gaps = SubmissionGaps.from(listOf(aRestored(120802, start)))

        gaps.sincePrevious(120802, start.plusSeconds(45)) shouldBe 45
    }

    @Test
    fun `it restores from the log across a restart`() {
        val gaps = SubmissionGaps.from(
            listOf(aRestored(120802, start), aRestored(120802, start.plusSeconds(600))),
        )

        gaps.sincePrevious(120802, start.plusSeconds(660)) shouldBe 60
    }

    /** The newest wins, not the last line: a correction re-appends an older record. */
    @Test
    fun `an out-of-order line does not move the latest backwards`() {
        val gaps = SubmissionGaps.from(
            listOf(aRestored(120802, start.plusSeconds(600)), aRestored(120802, start)),
        )

        gaps.sincePrevious(120802, start.plusSeconds(660)) shouldBe 60
    }

    /** A line whose timestamp will not parse restores nothing rather than a wrong gap. */
    @Test
    fun `a record with no readable timestamp restores no gap`() {
        val gaps = SubmissionGaps.from(listOf(aRestored(120802, ts = null)))

        gaps.sincePrevious(120802, start).shouldBeNull()
    }

    /**
     * An impossible duration is no reading, never a zero (dev rules §4). A clock that jumped
     * backwards must not produce a negative gap that later reads as a measurement.
     */
    @Test
    fun `a clock that went backwards yields no gap`() {
        val gaps = SubmissionGaps.from(listOf(aRestored(120802, start)))

        gaps.sincePrevious(120802, start.minusSeconds(30)).shouldBeNull()
    }

    private fun aRestored(lessonId: Long, ts: OffsetDateTime?) = RecordedSubmission(
        lessonId = lessonId,
        action = GradingAction.SUBMIT,
        attempt = 1,
        language = "java",
        ts = ts,
        line = "{}",
    )
}
