package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * `problems/<id>/runs.jsonl` — one line per run, holding the code it ran
 * (`docs/superpowers/specs/2026-10-07-mistake-patterns-design.md` §4.2).
 *
 * A run owns no attempt file and `Solution.<ext>` is overwritten by the next one, so before
 * this the code of every run but the last was lost. **Verdicts and messages live in
 * `log/submissions.jsonl`; this file holds only what the log cannot — the code — joined to its
 * record by `recordId`.** A second copy of the verdict would disagree with the log the day a
 * classification rule changes, as #350 just did.
 *
 * [RunLine.codeFetchedAt] is the instant the code was attached. A run attached late — the startup
 * retry after an expired session or a rate limit, or a second Run pressed within the ~0.3 s fetch
 * window — gets whatever code the page holds at fetch time, which may be a *later* run's. A reader
 * compares it with the next record's `ts` on the same problem (when that grading was recorded,
 * after it finished): fetched later than that, the code may belong to the next run. This catches a
 * late attachment; it can miss a second Run pressed within the fetch window. The server records
 * the fact; the reader decides.
 *
 * Full code on every line, since a late attachment arrives out of order and "same as the previous"
 * would name the wrong neighbour. Append-only and idempotent by [SubmissionRecord.recordId]: the
 * retry may attach a run twice. Only complete (newline-terminated) lines count as already written,
 * because a crash can leave a torn prefix of this very run's line.
 *
 * Appended through [RecordWrites]: a regular file or none, never through a link (#361). A refusal is
 * thrown, so the run's record keeps its code pending rather than claiming a line that was not kept.
 *
 * Read for the duplicate check through [ProblemFiles], #354's bound, as every reader under
 * `problems/` is (#387). Read through a link, a file elsewhere that held this run's id made the
 * append be skipped as done, with nothing refused. A link out now reads as no line, so the append
 * runs, and its own bound refuses the link and throws.
 */
class RunLog(private val layout: RecordLayout, private val clock: Clock) {
    private val writes = RecordWrites.underProblems(layout)
    private val files = ProblemFiles(layout)

    fun append(record: SubmissionRecord, code: String) {
        if (record.action != GradingAction.RUN) return
        val file = layout.runLog(record.lessonId, record.title)
        val id = record.recordId()
        if (alreadyHolds(file, id)) return
        writes.appendLine(file, format.encodeToString(RunLine(id, record.language, fetchedNow(), code)))
    }

    private fun fetchedNow(): String = OffsetDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun alreadyHolds(file: Path, recordId: String): Boolean {
        val bytes = files.readAllBytes(file) ?: return false
        val needle = "\"recordId\":${format.encodeToString(recordId)}"
        val complete = String(bytes, CHARSET).split('\n').dropLast(1)
        return complete.any { it.contains(needle) }
    }

    private companion object {
        val CHARSET = StandardCharsets.UTF_8
        val format = Json
    }
}

/**
 * One line of `runs.jsonl` — exactly these four keys (spec 2026-10-07 §4.2). Internal so the read
 * side, [FileGradingCodes], decodes the shape this file writes rather than a copy of it.
 */
@Serializable
internal data class RunLine(val recordId: String, val language: String, val codeFetchedAt: String, val code: String)
