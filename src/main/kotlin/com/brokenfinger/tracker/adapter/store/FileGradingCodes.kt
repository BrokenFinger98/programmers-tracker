package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.GradingCodes
import com.brokenfinger.tracker.domain.calc.KeptCode
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * The code kept beside the records, read back for repair steps (spec 2026-10-07 §4.3).
 *
 * Lenient in the posture of every reader here (dev rules §4): a torn line, an unreadable file, a
 * time that does not parse or a path that leaves `problems/` answers "not kept" for that one
 * grading and never fails the answer. A run-log line that cannot be read is not silent, though:
 * one warning per file says how many were left out — never a line, which holds code. It holds no
 * [RunLog] — the read side cannot append.
 *
 * A run appears once per `recordId`; should a line ever repeat, the first wins, as the writer's
 * idempotency check treats the first complete line as the record.
 */
class FileGradingCodes(private val layout: RecordLayout) : GradingCodes {
    override fun runs(lessonId: Long, title: String?): Map<String, KeptCode> =
        runCatching { keptIn(layout.runLog(lessonId, title)) }.getOrDefault(emptyMap())

    /**
     * A submit's code, from the path its record carries — and only from a regular file that really
     * lives under `problems/`.
     *
     * [RecordLayout.recordFile] bounds the path lexically. This follows the links too, because git
     * stores symbolic links and one can arrive with a clone or a pull as easily as by hand: a
     * `problems/1-x/attempts/001.java` linked to `.ps/git-credentials`, or a problem directory linked
     * out of `problems/`, passes the lexical bound and would hand the push token to the model. A link
     * that stays inside `problems/` is read as the file it names.
     *
     * A regular file only, as [keptIn] requires: a FIFO behind a record's path would block the
     * request thread for a writer that never comes. Anything that cannot be resolved or read — a
     * dangling link, a directory that is not there — is no code, never an exception.
     */
    override fun submitted(codePath: String): String? {
        val file = realFileUnderProblems(layout.recordFile(codePath)) ?: return null
        if (!Files.isRegularFile(file)) return null
        return runCatching { String(Files.readAllBytes(file), CHARSET) }.getOrNull()
    }

    // Both sides are resolved: the repository itself may sit behind a link (macOS's /var is one).
    private fun realFileUnderProblems(candidate: Path?): Path? {
        if (candidate == null) return null
        return runCatching {
            candidate.toRealPath().takeIf { it.startsWith(layout.problemsDirectory().toRealPath()) }
        }.getOrNull()
    }

    private fun keptIn(file: Path): Map<String, KeptCode> {
        if (!Files.isRegularFile(file)) return emptyMap()
        val lines = String(Files.readAllBytes(file), CHARSET).lines().filter(String::isNotBlank)
        val decoded = lines.map(::decoded)
        reportSkipped(file, decoded.count { it == null })
        return decoded.filterNotNull()
            .distinctBy { it.recordId }
            .associate { it.recordId to KeptCode(it.code, fetchedAtOf(it.codeFetchedAt)) }
    }

    // Counted, never quoted: a line holds code, and records stay out of logs (dev rules §7).
    private fun reportSkipped(file: Path, skipped: Int) {
        if (skipped == 0) return
        logger.warn("Left {} unreadable lines out of {}", skipped, file)
    }

    private fun decoded(line: String): RunLine? =
        runCatching { format.decodeFromString(RunLine.serializer(), line) }.getOrNull()

    // An unreadable time is an unknown one: the code is still the code, it just cannot be checked.
    private fun fetchedAtOf(text: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(text) }.getOrNull()

    private companion object {
        val CHARSET = StandardCharsets.UTF_8
        val format = Json { ignoreUnknownKeys = true }
        val logger = LoggerFactory.getLogger(FileGradingCodes::class.java)
    }
}
