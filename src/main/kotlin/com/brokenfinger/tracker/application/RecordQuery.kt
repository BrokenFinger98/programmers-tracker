package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.BrowsedProblem
import com.brokenfinger.tracker.domain.calc.CatalogBrowse
import com.brokenfinger.tracker.domain.calc.CatalogSummary
import com.brokenfinger.tracker.domain.calc.ProblemStatus
import com.brokenfinger.tracker.domain.calc.ReviewItem
import com.brokenfinger.tracker.domain.calc.ReviewQueue
import com.brokenfinger.tracker.domain.calc.Since
import com.brokenfinger.tracker.domain.calc.SlowPassReport
import com.brokenfinger.tracker.domain.calc.SlowPasses
import com.brokenfinger.tracker.domain.calc.SubmissionFilter
import com.brokenfinger.tracker.domain.calc.SubmissionTally
import com.brokenfinger.tracker.domain.calc.TallyBucket
import com.brokenfinger.tracker.domain.calc.TallyGroup
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Everything recorded about one problem.
 *
 * The catalog fields are nullable and stay that way: a problem whose title was never
 * recorded has **no** title here, rather than a filled-in placeholder. Design §7 says the
 * tools return raw data, and a stand-in the reader cannot distinguish from a measurement
 * is the mistake this repository has already made once
 * ([[concepts/assumption-vs-measurement]]).
 */
data class ProblemHistory(
    val lessonId: Long,
    val title: String?,
    val level: Int?,
    val part: String?,
    val acceptanceRate: Int?,
    val tags: List<String>,
    val submissions: List<SubmissionRecord>,
)

/**
 * The read side of the record repository — the half design §7 exposes over MCP.
 *
 * It only ever reads. Nothing here appends, moves or commits, so an AI holding the MCP
 * token cannot alter a solving record however it is prompted.
 *
 * Reads are lenient in the same posture as the writer's (dev rules §4). A crash mid-append
 * leaves a torn final line, and refusing to answer because of it would hide every record
 * before it — a grading Programmers has already broadcast can never be fetched again.
 */
class RecordQuery(private val store: RecordStore, private val catalog: ProblemCatalog, private val clock: Clock) {
    /**
     * The whole log, newest first, **each capture key at its latest state**.
     *
     * Through [RecordHistory] rather than decoding the lines directly, because the log is
     * append-only and a record that changes after it was written is appended again rather
     * than edited ([[decisions/2026-08-06-record-corrections-by-append]]). Read the raw lines
     * and every submission whose code was attached later comes back twice — once pending,
     * once complete — so `submissions` would list it twice and `stats` would count it twice.
     * The write side and the read side have to resolve corrections the same way; there is one
     * implementation so they cannot drift.
     *
     * The log is already in the order things happened, so it is reversed *before* sorting: the
     * sort is stable, and two records sharing a timestamp would otherwise come back
     * oldest-first inside a list that claims to be newest-first. Timestamps tie more often
     * than they look like they would — a `run` and the `submit` that follows it can land in
     * the same second.
     */
    fun history(): List<SubmissionRecord> = RecordHistory.of(store.read())
        .asReversed()
        .sortedByDescending { it.ts }

    fun submissions(since: Since?, verdict: Verdict?): List<SubmissionRecord> =
        SubmissionFilter.matching(history(), since, verdict)

    fun tally(group: TallyGroup): List<TallyBucket> = SubmissionTally.of(history(), group)

    /**
     * What is worth re-solving today (design §6.4). The clock is the only thing this adds to
     * the calculator — everything else it needs is in the records.
     */
    fun reviewQueue(limit: Int?): List<ReviewItem> = ReviewQueue.due(history(), OffsetDateTime.now(clock), limit)

    /** Passes ranked by their slowest testcase (design §6.5). No clock, and no baseline. */
    fun slowPasses(thresholdMs: Double?): SlowPassReport = SlowPasses.of(history(), thresholdMs)

    /**
     * The shipped catalog joined against what was recorded (#100).
     *
     * Reads the whole log once and hands two snapshots to the calculator, per dev rules §3
     * — no lookup happens mid-join, so the answer is a consistent picture of one moment
     * rather than a walk over a log that may be growing underneath it.
     *
     * @throws IllegalStateException never; a catalogue we do not own may simply be empty.
     */
    fun browse(
        level: Int? = null,
        part: String? = null,
        tag: String? = null,
        status: ProblemStatus? = null,
    ): List<BrowsedProblem> {
        val submits = history().filter { it.action == GradingAction.SUBMIT }
        return CatalogBrowse.of(
            catalogued = catalog.all().map { it.toSummary() },
            passedIds = submits.filter { it.verdict == Verdict.PASS }.map { it.lessonId }.toSet(),
            attemptsById = submits.groupingBy { it.lessonId }.eachCount(),
            level = level,
            part = part,
            tag = tag,
            status = status,
        )
    }

    private fun CatalogEntry.toSummary() = CatalogSummary(
        lessonId = id,
        title = title,
        level = level,
        part = partTitle,
        acceptanceRate = acceptanceRate,
        tags = tags,
    )

    /**
     * One problem and every recorded submission against it. A lesson with nothing recorded
     * is not an error — it answers with an empty history, which is the truthful statement
     * that we have observed nothing, not that nothing happened.
     */
    fun problem(lessonId: Long): ProblemHistory {
        val submissions = SubmissionFilter.ofProblem(history(), lessonId)
        return ProblemHistory(
            lessonId = lessonId,
            title = newest(submissions) { it.title.takeIf(String::isNotBlank) },
            level = newest(submissions) { it.level },
            part = newest(submissions) { it.part?.takeIf(String::isNotBlank) },
            acceptanceRate = newest(submissions) { it.acceptanceRate },
            tags = newest(submissions) { it.tags.takeIf(List<String>::isNotEmpty) }.orEmpty(),
            submissions = submissions,
        )
    }

    // Catalog metadata arrives late and unevenly, so the newest record that actually carries
    // a field wins rather than the newest record overall — otherwise one submission captured
    // before the catalog was consulted would erase a title we already know.
    private fun <T> newest(submissions: List<SubmissionRecord>, field: (SubmissionRecord) -> T?): T? =
        submissions.firstNotNullOfOrNull(field)
}
