package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Slowest first, with zero mocks (dev rules §6.1).
 *
 * `runTime` is a decimal string of **milliseconds**, and the protocol frame states the unit
 * itself: the same message carries `"run_time":"0.01"` beside a `msg` that spells the same
 * number as `(0.01ms, 75.3MB)` (protocol doc §5). Measured, not assumed.
 *
 * The case that matters most here is the missing one. A pass with no timing must never sort
 * as the fastest, and SQL never sends per-case timing at all, so the problems we know least
 * about would otherwise crowd the good end of the list.
 */
class SlowPassesTest {
    @Test
    fun `the slowest testcase is what a pass is judged on`() {
        val record = pass(lessonId = 1, times = listOf("68.69", "1636.97", "70.02"))

        val item = SlowPasses.of(listOf(record)).slow.single()

        item.slowestMs shouldBe 1636.97
        item.timedCases shouldBe 3
    }

    @Test
    fun `passes come back slowest first`() {
        val history = listOf(
            pass(lessonId = 1, times = listOf("70.0")),
            pass(lessonId = 2, times = listOf("1636.97")),
            pass(lessonId = 3, times = listOf("371.72")),
        )

        SlowPasses.of(history).slow.map { it.lessonId } shouldContainExactly listOf(2L, 3L, 1L)
    }

    /**
     * The ordering is the comparison (#134): one call hands the caller the whole distribution,
     * so "markedly slower" is theirs to decide and no baseline is invented here.
     */
    @Test
    fun `a threshold keeps only what reaches it`() {
        val history = listOf(
            pass(lessonId = 1, times = listOf("70.0")),
            pass(lessonId = 2, times = listOf("1636.97")),
        )

        SlowPasses.of(history, thresholdMs = 100.0).slow.map { it.lessonId } shouldContainExactly listOf(2L)
    }

    /**
     * SQL sends no per-case timing at all (protocol doc §6). Sorting it as `0` would put every
     * database problem at the fast end of a list about speed.
     */
    @Test
    fun `a pass with no timing is counted, not ranked`() {
        val history = listOf(
            pass(lessonId = 1, times = listOf("70.0")),
            pass(lessonId = 2, times = listOf(null, null)),
        )

        val answer = SlowPasses.of(history)

        answer.slow.map { it.lessonId } shouldContainExactly listOf(1L)
        answer.untimed shouldBe 1
    }

    /** A timeout or runtime error drops the timing case by case, not for the whole submission. */
    @Test
    fun `a submission timed in part is ranked on the cases that reported`() {
        val record = pass(lessonId = 1, times = listOf("70.0", null, "812.5"))

        val item = SlowPasses.of(listOf(record)).slow.single()

        item.slowestMs shouldBe 812.5
        item.timedCases shouldBe 2
    }

    @Test
    fun `only passes are ranked — a slow wrong answer is not a slow pass`() {
        val history = listOf(
            aSubmissionRecord(verdict = Verdict.WRONG, testcases = listOf(aTestcaseResult(runTime = "9999.0"))),
        )

        SlowPasses.of(history).slow.shouldBeEmpty()
    }

    /**
     * Only the newest pass of a problem, so re-solving it faster replaces the old reading
     * rather than leaving both in a list the reader has to de-duplicate by hand.
     */
    @Test
    fun `a re-solve replaces the earlier reading`() {
        val history = listOf(
            pass(lessonId = 1, times = listOf("1636.97"), day = 1),
            pass(lessonId = 1, times = listOf("70.0"), day = 30),
        )

        SlowPasses.of(history).slow.single().slowestMs shouldBe 70.0
    }

    /** Refused rather than rounded: a value the caller cannot have meant is not guessed at. */
    @Test
    fun `a runTime that is not a number is treated as no reading at all`() {
        val history = listOf(pass(lessonId = 1, times = listOf("n/a")))

        val answer = SlowPasses.of(history)

        answer.slow.shouldBeEmpty()
        answer.untimed shouldBe 1
    }

    // A reading belongs to a language (#173) -----------------------------------------------------

    /**
     * Grouping by problem alone kept one pass — the latest — so a slow Java pass written the day
     * after a fast Kotlin one disappeared entirely. That is the exact reading this calculator
     * exists to surface, hidden by the calculator itself.
     */
    @Test
    fun `a slow pass is not hidden by a faster one in another language`() {
        val history = listOf(
            pass(lessonId = 120804, times = listOf("0.01"), day = 1, language = "kotlin"),
            pass(lessonId = 120804, times = listOf("9.90"), day = 2, language = "java"),
        )

        val report = SlowPasses.of(history)

        report.slow.map { it.language } shouldContainExactly listOf("java", "kotlin")
        report.slow.map { it.slowestMs } shouldContainExactly listOf(9.90, 0.01)
    }

    /** The order that used to lose the slow one outright — the newer pass was the fast one. */
    @Test
    fun `the slow reading survives when the faster language was recorded later`() {
        val history = listOf(
            pass(lessonId = 120804, times = listOf("9.90"), day = 1, language = "java"),
            pass(lessonId = 120804, times = listOf("0.01"), day = 2, language = "kotlin"),
        )

        SlowPasses.of(history).slow.map { it.slowestMs } shouldContainExactly listOf(9.90, 0.01)
    }

    /** Within one language the latest pass still wins — re-solving replaces its own reading. */
    @Test
    fun `re-solving in the same language replaces that language's reading`() {
        val history = listOf(
            pass(lessonId = 120804, times = listOf("9.90"), day = 1),
            pass(lessonId = 120804, times = listOf("0.02"), day = 2),
        )

        SlowPasses.of(history).slow.map { it.slowestMs } shouldContainExactly listOf(0.02)
    }

    // Fixtures ---------------------------------------------------------------------------------

    private fun pass(lessonId: Long, times: List<String?>, day: Long = 1, language: String = "java") =
        aSubmissionRecord(
            ts = OffsetDateTime.parse("2026-01-01T09:00:00+09:00").plusDays(day - 1),
            lessonId = lessonId,
            language = language,
            verdict = Verdict.PASS,
            testcases = times.mapIndexed { index, time ->
                aTestcaseResult(id = index + 1L, passed = true, runTime = time)
            },
        )
}
