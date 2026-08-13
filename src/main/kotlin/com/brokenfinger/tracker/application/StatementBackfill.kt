package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SubmissionRecord
import org.slf4j.LoggerFactory

/**
 * Fills in the statements of problems solved before the server kept them (#280).
 *
 * #275 writes a statement while attaching a grading's code, which covers every future grading and
 * nothing before it. `attachPending` only revisits records still marked `codePending`, so a
 * problem whose code is already attached would get a statement **only if it were solved again** —
 * and a user adopting this tool after a year of solving keeps a year of records without one. The
 * gap grows with the history rather than shrinking.
 *
 * It also closes a retry hole: a first attachment that hit a rate limit or an expired session
 * never fetched the statement again for that problem, ever. This pass runs at every boot, so it
 * does.
 *
 * ### Nothing here needs a request to decide what to do
 *
 * The work list comes off disk. The record log carries `lessonId`, `title` and `language` for
 * every problem, and [ProblemStatements] says which of them already has a file. Requests go out
 * only for the ones that do not.
 *
 * ### Courtesy is the shape, not a footnote
 *
 * Development-rules §9.3 asks that this tool's requests stay at the level of what a browser does.
 * Five problems here; three hundred for someone with a real history, and three hundred fetches on
 * one start is exactly what that rule is about. So: a **cap per boot**, a **pause between**
 * fetches, and a **stop at the first blocking answer** — an expired session and a rate limit are
 * shared by every remaining problem, so learning it again 299 times is only rude.
 *
 * A backlog therefore drains over several boots, which is the right trade: nobody is waiting on it.
 */
class StatementBackfill(
    private val store: RecordStore,
    private val statements: ProblemStatements,
    private val source: ProblemStatementSource,
    private val artifacts: DerivedArtifacts,
    private val perBoot: Int = PER_BOOT,
    private val pause: suspend () -> Unit,
) {
    suspend fun run(): BackfillReport {
        val missing = missing()
        if (missing.isEmpty()) return BackfillReport()
        logger.info("Fetching the problem statement of {} problem(s) recorded before it was kept", missing.size)
        return passOver(missing)
    }

    /**
     * One record per problem — the newest, because it carries the title the directory is named
     * from and a language the problem definitely has a page for.
     */
    private fun missing(): List<SubmissionRecord> = RecordHistory.of(store.read())
        .asReversed()
        .distinctBy { it.lessonId }
        .filter { statements.of(it.lessonId, it.title) == null }
        .take(perBoot)

    private suspend fun passOver(missing: List<SubmissionRecord>): BackfillReport {
        var report = BackfillReport()
        for ((index, record) in missing.withIndex()) {
            if (index > 0) pause()
            report = report.and(filled(record))
            if (report.blocked) return report.also { logger.info("Stopped early: {}", it) }
        }
        return report.also { logger.info("Problem statements backfilled: {}", it) }
    }

    private suspend fun filled(record: SubmissionRecord): BackfillReport =
        when (val fetch = source.statementOf(record.lessonId, record.language.orEmpty())) {
            is StatementFetch.Fetched -> written(record, fetch.markdown)
            is StatementFetch.Blocked -> BackfillReport(blocked = true)
            is StatementFetch.Unavailable -> failed(record, fetch.reason)
        }

    /**
     * A write failure is not a reason to stop the pass, or the boot. The record is untouched
     * either way — this only ever adds a file beside one.
     */
    private fun written(record: SubmissionRecord, markdown: String): BackfillReport = runCatching {
        artifacts.writeStatement(record, markdown)
        BackfillReport(filled = 1)
    }.getOrElse { failed(record, it.javaClass.simpleName) }

    private fun failed(record: SubmissionRecord, reason: String): BackfillReport {
        logger.warn("Lesson {}: no statement stored ({})", record.lessonId, reason)
        return BackfillReport(failed = 1)
    }

    private companion object {
        /** Enough to finish an ordinary history in one or two boots, small enough to be polite. */
        const val PER_BOOT = 20

        val logger = LoggerFactory.getLogger(StatementBackfill::class.java)
    }
}

/** What one backfill pass did, in the shape [AttachReport] already established. */
data class BackfillReport(val filled: Int = 0, val failed: Int = 0, val blocked: Boolean = false) {
    fun and(other: BackfillReport): BackfillReport =
        BackfillReport(filled + other.filled, failed + other.failed, blocked || other.blocked)
}
