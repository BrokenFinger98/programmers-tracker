package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Stage 3 of the capture pipeline — the submission log
 * ([[decisions/2026-08-05-write-serialization.md]]).
 *
 * `log/submissions.jsonl` is the **single authority for `attempt`**: the number is restored
 * from here at startup and `attempts/NNN.*` names are derived from it. A directory scan is
 * deliberately not a second source, because two sources of the same number is both a race
 * and a reconciliation ambiguity.
 *
 * Appends are serialized by dispatcher confinement, not by this port. The port stays a plain
 * blocking interface so the file adapter has nothing to say about concurrency.
 */
interface RecordStore {
    /**
     * Appends one record as a single line. The caller owns the JSON shape (design §5.2);
     * the store only guarantees the line arrives whole and on a line of its own.
     */
    fun append(line: String)

    /**
     * Every record the log still holds, oldest first. **Lenient** — a line that cannot be
     * parsed is skipped, never thrown on, because a crash mid-append leaves a torn final
     * line and losing the rest of the log with it is unrecoverable (protocol doc §11).
     */
    fun read(): List<RecordedSubmission>
}

/**
 * The store's own projection of a record line — only the fields the attempt authority
 * depends on, plus the untouched original.
 *
 * It is deliberately *not* the full submission record: the writer owns that shape, and
 * duplicating it here would make every field addition a change in two places. [line] keeps
 * the original so nothing this projection ignores is actually lost (dev rules §2.4).
 */
data class RecordedSubmission(
    val lessonId: Long,
    /** Null when the action is one we do not recognise — the record is still kept. */
    val action: GradingAction?,
    val attempt: Int,
    val language: String?,
    val line: String,
) {
    companion object {
        /** A record that belongs to no attempt file yet — a run before the first submit. */
        const val NO_ATTEMPT = 0

        private val logger = LoggerFactory.getLogger(RecordedSubmission::class.java)

        // Unknown keys are ignored so a record written by a later version still restores the
        // attempt counter. Values are NOT lenient: leniency belongs at the line level, and a
        // silently coerced attempt number is exactly the wrong data this project fears most.
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Lenient creation from a stored line (dev rules §4) — never throws. Returns null for
         * a line that carries no usable identity, and the caller decides how loudly to say so.
         */
        fun ofReceived(line: String): RecordedSubmission? {
            if (line.isBlank()) return null
            val wire = runCatching { json.decodeFromString<Wire>(line) }.getOrNull() ?: return skipped("unreadable")
            val lessonId = wire.lessonId ?: return skipped("no lesson id")
            val action = GradingAction.ofReceived(wire.action)
            return RecordedSubmission(lessonId, action, wire.attempt ?: NO_ATTEMPT, wire.language, line)
        }

        // Never logs the line itself — a record carries a learner's solving history.
        private fun skipped(reason: String): RecordedSubmission? {
            logger.warn("Skipped a submission-log line ({})", reason)
            return null
        }
    }

    @Serializable
    private data class Wire(
        val lessonId: Long? = null,
        val action: String? = null,
        val attempt: Int? = null,
        val language: String? = null,
    )
}
