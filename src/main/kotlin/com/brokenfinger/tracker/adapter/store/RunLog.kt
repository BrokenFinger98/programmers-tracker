package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
 * [Line.codeFetchedAt] is the instant the code was attached. A run attached late — the startup
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
 */
class RunLog(private val layout: RecordLayout, private val clock: Clock) {
    fun append(record: SubmissionRecord, code: String) {
        if (record.action != GradingAction.RUN) return
        val file = layout.runLog(record.lessonId, record.title)
        val id = record.recordId()
        if (alreadyHolds(file, id)) return
        Files.createDirectories(file.parent)
        val line = format.encodeToString(Line(id, record.language, fetchedNow(), code))
        Files.writeString(file, heal(file) + line + "\n", CHARSET, *APPEND)
    }

    private fun fetchedNow(): String = OffsetDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun alreadyHolds(file: Path, recordId: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        val needle = "\"recordId\":${format.encodeToString(recordId)}"
        val complete = String(Files.readAllBytes(file), CHARSET).split('\n').dropLast(1)
        return complete.any { it.contains(needle) }
    }

    /** A last line cut short by a crash must not have the next one glued onto it. */
    private fun heal(file: Path): String {
        val size = if (Files.isRegularFile(file)) Files.size(file) else 0L
        if (size == 0L) return ""
        return if (lastByte(file, size) == NEWLINE) "" else "\n"
    }

    private fun lastByte(file: Path, size: Long): Byte = Files.newByteChannel(file).use { channel ->
        val buffer = ByteBuffer.allocate(1)
        channel.position(size - 1).read(buffer)
        buffer.get(0)
    }

    @Serializable
    private data class Line(val recordId: String, val language: String, val codeFetchedAt: String, val code: String)

    private companion object {
        val CHARSET = StandardCharsets.UTF_8
        val APPEND = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        const val NEWLINE = '\n'.code.toByte()
        val format = Json
    }
}
