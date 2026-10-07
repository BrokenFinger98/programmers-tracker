package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Zero mocks. Counts of problems, never a judgement about them (spec 2026-10-07 §4.3). */
class ProblemProgressTest {
    @Test
    fun `counts problems, not submits`() {
        val progress = ProblemProgress.of(
            listOf(
                submit(lessonId = 1, at = "10:00", verdict = Verdict.WRONG),
                submit(lessonId = 1, at = "10:05", verdict = Verdict.PASS),
                submit(lessonId = 2, at = "11:00", verdict = Verdict.WRONG),
            ),
        )

        progress.attempted shouldBe 2
        progress.passed shouldBe 1
        progress.passedFirstSubmit shouldBe 0
    }

    /** A run is not an attempt (design §5.1): a problem only ever run was not attempted. */
    @Test
    fun `a problem only ever run was not attempted`() {
        ProblemProgress.of(listOf(run(lessonId = 1, at = "10:00"))).attempted shouldBe 0
    }

    @Test
    fun `a problem whose first submit passed is counted as passing first time`() {
        val progress = ProblemProgress.of(
            listOf(submit(lessonId = 1, at = "10:00", verdict = Verdict.PASS), run(lessonId = 1, at = "09:00")),
        )

        progress.passedFirstSubmit shouldBe 1
    }

    @Test
    fun `runs before the first passing submit count, runs after it do not`() {
        val progress = ProblemProgress.of(
            listOf(
                run(lessonId = 1, at = "09:00"),
                run(lessonId = 1, at = "09:01"),
                submit(lessonId = 1, at = "09:02", verdict = Verdict.PASS),
                run(lessonId = 1, at = "09:03"),
            ),
        )

        progress.runsBeforePass shouldBe 2.0
    }

    @Test
    fun `the median of an even count is the mean of the middle two`() {
        val progress = ProblemProgress.of(
            listOf(run(lessonId = 1, at = "09:00"), submit(lessonId = 1, at = "09:10", verdict = Verdict.PASS)) +
                (0..3).map { run(lessonId = 2, at = "10:0$it") } +
                submit(lessonId = 2, at = "10:10", verdict = Verdict.PASS),
        )

        progress.runsBeforePass shouldBe 2.5
    }

    @Test
    fun `the median of an odd count is the middle one`() {
        val progress = ProblemProgress.of(
            listOf(
                submit(lessonId = 1, at = "09:00", verdict = Verdict.PASS),
                run(lessonId = 2, at = "09:00"),
                submit(lessonId = 2, at = "09:10", verdict = Verdict.PASS),
                run(lessonId = 3, at = "09:00"),
                run(lessonId = 3, at = "09:01"),
                run(lessonId = 3, at = "09:02"),
                submit(lessonId = 3, at = "09:10", verdict = Verdict.PASS),
            ),
        )

        progress.runsBeforePass shouldBe 1.0
    }

    /** Absent is not zero: with no passed problem there is nothing to take a median of. */
    @Test
    fun `no passed problem has no runs-before-pass rather than zero`() {
        ProblemProgress.of(listOf(submit(lessonId = 1, at = "10:00", verdict = Verdict.WRONG)))
            .runsBeforePass.shouldBeNull()
    }

    @Test
    fun `a run in another language before the pass counts too`() {
        val progress = ProblemProgress.of(
            listOf(
                run(lessonId = 1, at = "09:00", language = "kotlin"),
                submit(lessonId = 1, at = "09:10", verdict = Verdict.PASS),
            ),
        )

        progress.runsBeforePass shouldBe 1.0
    }

    /** Policy: an unresolved first submit is not a PASS, and it is not excluded either. */
    @Test
    fun `a first submit that never resolved is not a first-time pass`() {
        val unresolved = aSubmissionRecord(
            lessonId = 1,
            ts = at("10:00"),
            action = GradingAction.SUBMIT,
            outcome = Outcome.UNKNOWN,
            verdict = null,
        )

        val pass = submit(lessonId = 1, at = "10:05", verdict = Verdict.PASS)
        val progress = ProblemProgress.of(listOf(unresolved, pass))

        progress.attempted shouldBe 1
        progress.passed shouldBe 1
        progress.passedFirstSubmit shouldBe 0
    }

    @Test
    fun `a pass in another language after a failure is not a first-time pass`() {
        val progress = ProblemProgress.of(
            listOf(
                submit(lessonId = 1, at = "10:00", verdict = Verdict.WRONG),
                submit(lessonId = 1, at = "10:05", verdict = Verdict.PASS, language = "kotlin"),
            ),
        )

        progress.passed shouldBe 1
        progress.passedFirstSubmit shouldBe 0
    }

    @Test
    fun `the earliest pass across languages ends the runs-before-pass window`() {
        val progress = ProblemProgress.of(
            listOf(
                submit(lessonId = 1, at = "10:10", verdict = Verdict.PASS, language = "kotlin"),
                run(lessonId = 1, at = "10:05"),
                submit(lessonId = 1, at = "10:20", verdict = Verdict.PASS),
            ),
        )

        progress.runsBeforePass shouldBe 1.0
    }

    @Test
    fun `runs between a failed submit and the first pass count`() {
        val progress = ProblemProgress.of(
            listOf(
                submit(lessonId = 1, at = "10:00", verdict = Verdict.WRONG),
                run(lessonId = 1, at = "10:01"),
                run(lessonId = 1, at = "10:02"),
                submit(lessonId = 1, at = "10:03", verdict = Verdict.PASS),
            ),
        )

        progress.runsBeforePass shouldBe 2.0
    }

    @Test
    fun `a run at exactly the pass instant is not before it`() {
        val progress = ProblemProgress.of(
            listOf(run(lessonId = 1, at = "10:00"), submit(lessonId = 1, at = "10:00", verdict = Verdict.PASS)),
        )

        progress.runsBeforePass shouldBe 0.0
    }

    @Test
    fun `perBucket keys each problem by its newest known level`() {
        val records = listOf(
            aSubmissionRecord(lessonId = 1, level = null, ts = OffsetDateTime.parse("2026-10-01T10:00:00+09:00")),
            aSubmissionRecord(lessonId = 1, level = 2, ts = OffsetDateTime.parse("2026-10-01T10:05:00+09:00")),
        )

        val buckets = ProblemProgress.perBucket(records, TallyGroup.LEVEL)

        buckets.keys shouldBe setOf("2")
    }

    private fun at(time: String) = OffsetDateTime.parse("2026-10-01T$time:00+09:00")

    private fun submit(lessonId: Long, at: String, verdict: Verdict, language: String = "java") = aSubmissionRecord(
        lessonId = lessonId,
        language = language,
        ts = OffsetDateTime.parse("2026-10-01T$at:00+09:00"),
        action = GradingAction.SUBMIT,
        verdict = verdict,
    )

    private fun run(lessonId: Long, at: String, language: String = "java") = aSubmissionRecord(
        lessonId = lessonId,
        ts = OffsetDateTime.parse("2026-10-01T$at:00+09:00"),
        action = GradingAction.RUN,
        attempt = 0,
        language = language,
        verdict = Verdict.WRONG,
    )
}
