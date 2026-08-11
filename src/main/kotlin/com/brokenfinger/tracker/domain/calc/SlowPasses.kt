package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.Verdict
import java.time.OffsetDateTime

/**
 * One passed problem and the slowest testcase it was measured at.
 *
 * Everything a comparison needs travels with it — level, tags, language — because the caller
 * is the one deciding what "markedly slower" means
 * ([[decisions/2026-08-10-scheduling-is-not-diagnosis]]). §6.5 asks for a comparison against
 * same-tag, same-level problems, and there are not enough recorded passes to have peers; the
 * ordering is what stands in for it, so the facts that let a reader group the list have to
 * be here.
 */
data class SlowPass(
    val lessonId: Long,
    val title: String,
    val level: Int?,
    val tags: List<String>,
    val language: String,
    val passedAt: OffsetDateTime,
    /** Milliseconds — the unit the protocol frame states beside the value (protocol doc §5). */
    val slowestMs: Double,
    /** Which case was the slowest, so it can be opened and looked at. */
    val slowestCaseId: Long,
    /** How many cases reported a time. Fewer than the problem has means some did not. */
    val timedCases: Int,
)

/**
 * Every timed pass, and the count of the ones that could not be timed.
 *
 * The second number is not a footnote. A pass with no reading is excluded from the ordering
 * rather than sorted as zero, and a reader who cannot see how many were excluded would read
 * the list as the whole picture.
 */
data class SlowPassReport(val slow: List<SlowPass>, val untimed: Int)

/**
 * Passing is not the end (design §6.5).
 *
 * `runTime` arrives per testcase, and a pass far slower than its neighbours means the intended
 * solution was missed — which an efficiency test in a real exam scores as an outright fail.
 *
 * **No baseline is invented.** §6.5 asks for "markedly slower than same-tag/same-level", which
 * needs peers this record set does not have yet. So the whole distribution comes back in one
 * call, slowest first, and the caller decides where the line is — the same resolution the
 * review queue reached for a threshold nothing could calibrate.
 *
 * Pure by construction (dev rules §3): a snapshot in, a report out.
 */
object SlowPasses {
    fun of(history: List<SubmissionRecord>, thresholdMs: Double? = null): SlowPassReport {
        // By (lesson, language), not by lesson. Grouping by problem alone kept one pass —
        // the latest — so a slow Java pass vanished behind a fast Kotlin one written the day
        // after, which is precisely the reading this calculator exists to surface (#173). A
        // runtime is a property of the measurement, not of the problem.
        val passes = history.filter { it.passed() }.groupBy { it.lessonId to it.language }
            .map { (_, records) -> records.maxBy { it.ts } }
        val timed = passes.mapNotNull { slowPassOf(it) }
        return SlowPassReport(
            slow = timed.filter {
                thresholdMs == null || it.slowestMs >= thresholdMs
            }.sortedByDescending { it.slowestMs },
            untimed = passes.size - timed.size,
        )
    }

    private fun slowPassOf(pass: SubmissionRecord): SlowPass? {
        val timed = pass.testcases.mapNotNull { case -> case.millis()?.let { case to it } }
        val slowest = timed.maxByOrNull { (_, ms) -> ms } ?: return null
        return slowPassOf(pass, slowest.first, slowest.second, timed.size)
    }

    private fun slowPassOf(pass: SubmissionRecord, case: TestcaseResult, ms: Double, timedCases: Int) = SlowPass(
        lessonId = pass.lessonId,
        title = pass.title,
        level = pass.level,
        tags = pass.tags,
        language = pass.language,
        passedAt = pass.ts,
        slowestMs = ms,
        slowestCaseId = case.id,
        timedCases = timedCases,
    )

    // Lenient in the receive-first posture (dev rules §4): a value we cannot read as a number
    // is no reading, never a zero. Zero would sort the cases we understand least as the
    // fastest, in a list whose whole subject is speed.
    private fun TestcaseResult.millis(): Double? = runTime?.trim()?.toDoubleOrNull()

    private fun SubmissionRecord.passed(): Boolean = action == GradingAction.SUBMIT && verdict == Verdict.PASS
}
