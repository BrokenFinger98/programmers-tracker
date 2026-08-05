package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.Since
import com.brokenfinger.tracker.domain.calc.SubmissionFilter
import com.brokenfinger.tracker.domain.calc.SubmissionTally
import com.brokenfinger.tracker.domain.calc.TallyBucket
import com.brokenfinger.tracker.domain.calc.TallyGroup
import org.slf4j.LoggerFactory

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
class RecordQuery(private val store: RecordStore) {
    /**
     * The whole log, newest first. A line that is not a readable record is skipped.
     *
     * The log is append-only and therefore already in the order things happened, so it is
     * reversed *before* sorting: the sort is stable, and two records sharing a timestamp
     * would otherwise come back oldest-first inside a list that claims to be newest-first.
     * Timestamps tie more often than they look like they would — a `run` and the `submit`
     * that follows it can land in the same second.
     */
    fun history(): List<SubmissionRecord> = store.read()
        .mapNotNull(::decode)
        .asReversed()
        .sortedByDescending { it.ts }

    fun submissions(since: Since?, verdict: Verdict?): List<SubmissionRecord> =
        SubmissionFilter.matching(history(), since, verdict)

    fun tally(group: TallyGroup): List<TallyBucket> = SubmissionTally.of(history(), group)

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

    private fun decode(recorded: RecordedSubmission): SubmissionRecord? =
        runCatching { SubmissionRecordJson.decode(recorded.line) }.getOrElse { skipped() }

    // Never logs the line: a record carries a learner's solving history (dev rules §7).
    private fun skipped(): SubmissionRecord? {
        logger.warn("Skipped a submission-log line the reader could not decode as a full record")
        return null
    }

    private companion object {
        val logger = LoggerFactory.getLogger(RecordQuery::class.java)!!
    }
}
