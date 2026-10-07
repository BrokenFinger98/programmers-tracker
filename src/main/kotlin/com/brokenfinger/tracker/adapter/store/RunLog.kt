package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `problems/<id>/runs.jsonl` — one line per run, with the code it ran
 * (`docs/superpowers/specs/2026-10-07-mistake-patterns-design.md` §4.2).
 *
 * A run owns no attempt file and `Solution.<ext>` is overwritten by the next one, so before
 * this the code of every run but the last was lost — and the correction between a failed run
 * and the next is the evidence a recurring mistake leaves. Full code on every line: a few KB at
 * most, and a pending run attached late by the startup retry arrives out of order, so a line
 * that said "same as the previous" would name the wrong neighbour.
 *
 * Append-only and idempotent by [SubmissionRecord.recordId]: the retry may attach a run twice.
 * Not read on the capture path beyond that check.
 */
class RunLog(private val layout: RecordLayout) {
    fun append(record: SubmissionRecord, code: String) {
        if (record.action != GradingAction.RUN) return
        val file = layout.runLog(record.lessonId, record.title)
        if (alreadyHolds(file, record.recordId())) return
        Files.createDirectories(file.parent)
        val line = format.encodeToString(lineOf(record, code))
        Files.writeString(file, heal(file) + line + "\n", CHARSET, *APPEND)
    }

    private fun lineOf(record: SubmissionRecord, code: String) = RunLine(
        recordId = record.recordId(),
        ts = record.recordId().substringBefore('#'),
        language = record.language,
        outcome = record.outcome.name,
        verdict = record.verdict?.name,
        failedMessage = record.testcases.sortedBy { it.id }.firstOrNull { it.hasFailed() }?.msg,
        errorText = record.errorText,
        code = code,
    )

    private fun alreadyHolds(file: Path, recordId: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        val needle = "\"recordId\":${format.encodeToString(recordId)}"
        return String(Files.readAllBytes(file), CHARSET).lineSequence().any { it.contains(needle) }
    }

    /** A last line cut short by a crash must not have the next one glued onto it. */
    private fun heal(file: Path): String {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return ""
        return if (Files.readAllBytes(file).last() == '\n'.code.toByte()) "" else "\n"
    }

    @Serializable
    private data class RunLine(
        val recordId: String,
        val ts: String,
        val language: String,
        val outcome: String,
        val verdict: String?,
        val failedMessage: String?,
        val errorText: String?,
        val code: String,
    )

    private companion object {
        val CHARSET = StandardCharsets.UTF_8
        val APPEND = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        val format = Json { explicitNulls = false }
    }
}
