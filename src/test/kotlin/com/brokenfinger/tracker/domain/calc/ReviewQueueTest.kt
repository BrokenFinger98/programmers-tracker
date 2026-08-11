package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SensorObservation
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSensorObservation
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The schedule, computed with zero mocks (dev rules §6.1).
 *
 * Design §6.4 states two anchors and this reproduces both, because a formula that agrees with
 * the document that asked for it is the only calibration available — there is no measured
 * distribution of how long a problem *should* take, and #132 says so rather than inventing one.
 *
 * The direction of every error matters more than its size here. Reading "we did not observe"
 * as "no help was taken" is what pushes a shaky problem two months out, so absence is tested
 * as its own case rather than folded in with `false`.
 */
class ReviewQueueTest {
    // Design §6.4: "1st try, no hints, 10 min → high confidence → 60 days later".
    @Test
    fun `a first-try pass with no help earns the longest interval`() {
        val history = listOf(pass(day = 1, sensor = aSensorObservation(sawQuestions = false)))

        val item = ReviewQueue.due(history, now = at(61)).single()

        item.confidence shouldBe Confidence.HIGH
        item.dueAt shouldBe day(61)
        item.attempts shouldBe 1
    }

    // Design §6.4: "5 wrong tries, level-3 hints → low confidence → 3 days later".
    @Test
    fun `a pass after five failures with help seen earns the shortest`() {
        val history = failures(4, from = 1) + pass(day = 1, sensor = aSensorObservation(sawQuestions = true))

        val item = ReviewQueue.due(history, now = at(4)).single()

        item.confidence shouldBe Confidence.SHAKY
        item.dueAt shouldBe day(4)
        item.attempts shouldBe 5
    }

    /**
     * The one error direction that matters. A record written without the extension says
     * nothing about whether help was taken, and treating that as "none was" would buy the
     * longest interval with an absence.
     */
    @Test
    fun `a pass with no sensor reading cannot reach the longest interval`() {
        val history = listOf(pass(day = 1, sensor = null))

        val item = ReviewQueue.due(history, now = at(22)).single()

        item.confidence shouldBe Confidence.MEDIUM
        item.sawQuestions.shouldBeNull()
    }

    @Test
    fun `a problem still inside its interval is not due`() {
        val history = listOf(pass(day = 1, sensor = aSensorObservation()))

        ReviewQueue.due(history, now = at(60)).shouldBeEmpty()
    }

    @Test
    fun `a problem that was never passed is not in the queue`() {
        ReviewQueue.due(failures(3, from = 1), now = at(400)).shouldBeEmpty()
    }

    /** The queue is a work list, so what is most overdue comes first. */
    @Test
    fun `the most overdue problem comes first`() {
        val history = listOf(
            pass(day = 100, lessonId = 1, sensor = aSensorObservation(sawQuestions = true)),
            pass(day = 1, lessonId = 2, sensor = aSensorObservation(sawQuestions = true)),
        )

        ReviewQueue.due(history, now = at(400)).map { it.lessonId } shouldContainExactly listOf(2L, 1L)
    }

    /**
     * Re-solving must lengthen the interval, never shorten it. Counting every submit the
     * problem ever had would make a second pass look shakier than the first — the opposite of
     * what passing again means — so attempts are counted from the previous pass.
     */
    @Test
    fun `a re-solve is judged on its own attempts, not the problem's whole history`() {
        val history = failures(4, from = 1) +
            pass(day = 1, sensor = aSensorObservation(sawQuestions = true)) +
            pass(day = 100, sensor = aSensorObservation(sawQuestions = false))

        val item = ReviewQueue.due(history, now = at(400)).single()

        item.attempts shouldBe 1
        item.confidence shouldBe Confidence.HIGH
    }

    /** A run is not an attempt at solving it; pressing it is how you write the code. */
    @Test
    fun `runs are not counted as attempts`() {
        val history = listOf(
            run(day = 1, seq = 0),
            run(day = 1, seq = 1),
            pass(day = 1, sensor = aSensorObservation(sawQuestions = false)),
        )

        ReviewQueue.due(history, now = at(61)).single().attempts shouldBe 1
    }

    @Test
    fun `the limit caps the queue without reordering it`() {
        val history = listOf(
            pass(day = 100, lessonId = 1, sensor = aSensorObservation(sawQuestions = true)),
            pass(day = 1, lessonId = 2, sensor = aSensorObservation(sawQuestions = true)),
        )

        ReviewQueue.due(history, now = at(400), limit = 1).map { it.lessonId } shouldContainExactly listOf(2L)
    }

    /** Reported, never scored (#132) — the AI weighs it, the server does not pretend to. */
    @Test
    fun `focused seconds are carried through untouched`() {
        val history = listOf(pass(day = 1, sensor = aSensorObservation(focusedSec = 4231)))

        ReviewQueue.due(history, now = at(400)).single().focusedSec shouldBe 4231
    }

    /**
     * The day boundary is the learner's, not the server's. This pass was recorded at +09:00
     * and its sixtieth day has begun there; the server process behind it runs in UTC, where
     * the same instant is still the day before. Judging in UTC would hold the item back a day,
     * every day, for as long as the container's zone and the learner's disagree — measured on
     * the running image, which reports `UTC`.
     */
    @Test
    fun `due is decided in the offset the pass was recorded in, not the server's`() {
        val history = listOf(pass(day = 1, sensor = aSensorObservation(sawQuestions = false)))

        // 2026-03-02T00:30+09:00 — the sixtieth day in Seoul, still the fifty-ninth in UTC.
        val queue = ReviewQueue.due(history, now = OffsetDateTime.parse("2026-03-01T15:30:00Z"))

        queue.single().overdueDays shouldBe 0
    }

    /**
     * The bands between the two anchors, which the anchors alone never reach. A calculator
     * whose middle is untested is one that only works at the ends — and the coverage gate
     * said so before this was written.
     */
    @Test
    fun `the bands between the two anchors`() {
        val cases = listOf(
            Triple(1, false, Confidence.HIGH),
            Triple(2, false, Confidence.MEDIUM),
            Triple(4, false, Confidence.MEDIUM),
            Triple(5, false, Confidence.LOW),
            Triple(3, true, Confidence.LOW),
            Triple(5, true, Confidence.SHAKY),
        )

        cases.forEach { (attempts, sawQuestions, expected) ->
            val history = failures(attempts - 1, from = 1) +
                pass(day = 1, sensor = aSensorObservation(sawQuestions = sawQuestions))

            val item = ReviewQueue.due(history, now = at(400)).single()

            withClue("$attempts attempts, sawQuestions=$sawQuestions") {
                item.attempts shouldBe attempts
                item.confidence shouldBe expected
            }
        }
    }

    /**
     * The cap only removes the top band. A pass that had already earned something lower keeps
     * it — clamping everything to MEDIUM would make an unwatched five-attempt struggle look
     * better than it was, which is the same error the cap exists to prevent.
     */
    @Test
    fun `an unobserved pass keeps a band it had already earned`() {
        val history = failures(4, from = 1) + pass(day = 1, sensor = null)

        ReviewQueue.due(history, now = at(400)).single().confidence shouldBe Confidence.LOW
    }

    // A pass is per language (#173) ------------------------------------------------------------
    //
    // The owner's case: someone who normally solves in Kotlin and has to practise Java because a
    // company does not offer Kotlin. Grouping by lesson alone told them they were done with a
    // problem they had never once solved in the language they were practising.

    @Test
    fun `a problem passed in two languages is two items on two schedules`() {
        val history = listOf(
            pass(day = 1, sensor = aSensorObservation(sawQuestions = false)),
            pass(day = 40, language = "kotlin", sensor = aSensorObservation(sawQuestions = false)),
        )

        // Day 110: the java pass fell due on day 61, the kotlin one on day 100.
        val due = ReviewQueue.due(history, now = at(110))

        due.map { it.language } shouldContainExactly listOf("java", "kotlin")
        due.map { it.lessonId } shouldContainExactly listOf(120804L, 120804L)
    }

    /**
     * The direction that matters. A pass in one language must not schedule another, or a learner
     * practising Java is told a Kotlin pass covered it.
     */
    @Test
    fun `passing in one language leaves the other language's schedule untouched`() {
        val history = listOf(
            pass(day = 1, sensor = aSensorObservation(sawQuestions = false)),
            pass(day = 60, language = "kotlin", sensor = aSensorObservation(sawQuestions = false)),
        )

        val due = ReviewQueue.due(history, now = at(65))

        due.map { it.language } shouldContainExactly listOf("java")
    }

    /**
     * Attempts are counted within the language too. Counting the problem's whole history would
     * make a first Java attempt look shaky because of Kotlin submits that taught nothing about
     * writing it in Java.
     */
    @Test
    fun `a language's attempt count ignores the other language's submits`() {
        val history = failures(count = 4, from = 1) +
            listOf(pass(day = 1, language = "kotlin", sensor = aSensorObservation(sawQuestions = false)))

        val kotlin = ReviewQueue.due(history, now = at(200)).single { it.language == "kotlin" }

        kotlin.attempts shouldBe 1
        kotlin.confidence shouldBe Confidence.HIGH
    }

    /** One problem now yields two items, so the order has to stay total across them. */
    @Test
    fun `two items for one problem keep a stable order`() {
        val history = listOf(
            pass(day = 1, language = "kotlin", sensor = aSensorObservation(sawQuestions = false)),
            pass(day = 1, language = "java", sensor = aSensorObservation(sawQuestions = false)),
        )

        val first = ReviewQueue.due(history, now = at(70)).map { it.language }
        val second = ReviewQueue.due(history.reversed(), now = at(70)).map { it.language }

        first shouldContainExactly listOf("java", "kotlin")
        second shouldContainExactly first
    }

    // Fixtures ---------------------------------------------------------------------------------

    private fun day(n: Long): LocalDate = LocalDate.parse("2026-01-01").plusDays(n - 1)

    // Seconds apart within the day, so "which came first" is never a tie the sort has to guess.
    private fun at(day: Long, seq: Long = 0) =
        OffsetDateTime.parse("2026-01-01T09:00:00+09:00").plusDays(day - 1).plusSeconds(seq)

    private fun pass(day: Long, lessonId: Long = 120804, language: String = "java", sensor: SensorObservation?) =
        aSubmissionRecord(
            ts = at(day, PASSES_LAST),
            lessonId = lessonId,
            language = language,
            verdict = Verdict.PASS,
            sensor = sensor,
        )

    private fun failures(count: Int, from: Long) = (0 until count).map {
        aSubmissionRecord(ts = at(from, it.toLong()), verdict = Verdict.WRONG, sensor = null)
    }

    private fun run(day: Long, seq: Long) =
        aSubmissionRecord(ts = at(day, seq), action = GradingAction.RUN, verdict = null)

    private companion object {
        /** After anything `failures` or `run` staged on the same day. */
        const val PASSES_LAST = 3600L
    }
}
