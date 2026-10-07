package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.SubmissionRecord
import java.time.OffsetDateTime

/**
 * A grading's code as it was kept. [fetchedAt] is when it was attached — known for a run
 * (`runs.jsonl`'s `codeFetchedAt`), absent for a submit, whose attempt file records no such time.
 */
data class KeptCode(val text: String, val fetchedAt: OffsetDateTime?)

/**
 * One grading as repair steps see it: the record, its code when kept, and whether that code was
 * attached after the problem's next grading had been recorded — in which case it may be that
 * grading's code (spec 2026-10-07 §4.2).
 */
data class CodedGrading(val record: SubmissionRecord, val code: KeptCode?, val late: Boolean)

/**
 * One problem's gradings in time order, each with its kept code (dev rules §3 — no I/O).
 *
 * **Late is decided against the problem's next grading in any language**, which is why this takes
 * the whole problem rather than one language: the rule compares the fetch with the next record of
 * the problem, and a timeline cut to one language cannot see it.
 *
 * The next record's `ts` is when that grading was **recorded**, after it finished — not when it
 * started. So the rule catches a late attachment (the startup retry after an expired session, a
 * fetch that lagged past the next grading) but cannot see a race inside the ~0.3 s the fetch
 * takes: edited code and a second Run pressed before the fetch lands are fetched as this
 * grading's, yet arrive before the second grading is recorded, and read as not late
 * ([[decisions/2026-10-07-every-run-keeps-its-code]]).
 *
 * The sort is stable, so gradings that share a timestamp keep the order they were handed in —
 * **the caller must pass them in log order** (oldest first; `RecordQuery` merges runs and submits
 * from the log, which is that order). A tie also makes the tied partner the "next" grading, so a
 * run whose code was fetched ~0.3 s after its own record is reported late when another record
 * shares its `ts`: a conservative false positive, never a missed one.
 */
object CodeTimeline {
    fun of(records: List<SubmissionRecord>, codes: Map<String, KeptCode>): List<CodedGrading> {
        require(records.map { it.lessonId }.distinct().size <= 1) { "a code timeline is one problem's" }
        val ordered = records.sortedBy { it.ts }
        return ordered.mapIndexed { index, record ->
            coded(record, codes[record.recordId()], ordered.getOrNull(index + 1))
        }
    }

    private fun coded(record: SubmissionRecord, code: KeptCode?, next: SubmissionRecord?): CodedGrading =
        CodedGrading(record, code, isLate(code, next))

    private fun isLate(code: KeptCode?, next: SubmissionRecord?): Boolean {
        val fetchedAt = code?.fetchedAt ?: return false
        if (next == null) return false
        return fetchedAt.isAfter(next.ts)
    }
}
