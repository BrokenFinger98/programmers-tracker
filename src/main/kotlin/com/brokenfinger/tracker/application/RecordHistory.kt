package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import org.slf4j.LoggerFactory

/**
 * The corrected view of `log/submissions.jsonl`.
 *
 * The log is append-only, because that is what lets it be the attempt authority: a number
 * once allocated is never rewritten ([[decisions/2026-08-05-write-serialization]] decision 2).
 * A record that changes after it was written — today only stage 3 clearing `codePending` —
 * is therefore appended again rather than edited, and **the newest line for a capture key is
 * the record**. Every reader that wants record state rather than raw lines comes through here.
 *
 * The superseding line takes the position of the one it replaces, so a correction appended an
 * hour later cannot reorder a problem's attempt history in its README.
 *
 * Lenient, in the same posture as protocol parsing (dev rules §4): a line no record can be
 * read from is left out with a warning, never thrown on. A crash mid-append leaves a torn
 * final line, and losing the whole history to it would cost far more than one record.
 */
object RecordHistory {
    /** Every record the log still holds, oldest first, each capture key at its latest state. */
    fun of(records: List<RecordedSubmission>): List<SubmissionRecord> =
        records.mapNotNull { decoded(it.line) }.associateBy { it.captureKey }.values.toList()

    private fun decoded(line: String): SubmissionRecord? =
        runCatching { SubmissionRecordJson.decode(line) }.getOrElse { skipped() }

    // The line itself is never logged — a record carries a learner's solving history (dev rules §7).
    private fun skipped(): SubmissionRecord? {
        logger.warn("A submission-log line could not be read as a record and was left out of the history")
        return null
    }

    private val logger = LoggerFactory.getLogger(RecordHistory::class.java)
}
