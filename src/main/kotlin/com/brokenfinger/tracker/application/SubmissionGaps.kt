package com.brokenfinger.tracker.application

import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * How long it has been since this problem was last graded.
 *
 * The gap between attempts is the difference between five submits in ninety seconds and five
 * submits across three evenings. The first is guessing; the second is thinking, and nothing in a
 * record could tell them apart — `sincePrevSec` was in the design, the schema and every fixture,
 * and null in all 80 records because nothing ever set it (#207).
 *
 * Restored from the submission log at startup and advanced in memory afterwards, the same shape
 * as [AttemptAuthority] and for the same reason: the log is the one authority, and a second
 * source for the same number would be a read-then-write race.
 *
 * **Any action, any language.** The log holds runs too, and "five runs in two minutes" is the
 * same signal as five submits. Language is not part of the key here, unlike the scheduling rule
 * in [[decisions/2026-08-11-a-pass-belongs-to-its-language]]: that decision is about whether a
 * *pass* demonstrates a language, while this asks how long since the learner last touched the
 * problem — and switching language is still touching it.
 */
class SubmissionGaps private constructor(private val lastGraded: ConcurrentHashMap<Long, OffsetDateTime>) {
    /**
     * Seconds since the previous grading of this problem, and remembers [at] as the new latest.
     *
     * Null only when there genuinely is no previous one. A clock that went backwards yields null
     * rather than a negative: an impossible duration is no reading, never a zero (dev rules §4).
     */
    fun sincePrevious(lessonId: Long, at: OffsetDateTime): Long? {
        val previous = lastGraded.put(lessonId, at) ?: return null
        val seconds = Duration.between(previous, at).seconds
        return seconds.takeIf { it >= 0 }
    }

    companion object {
        /**
         * Restores the latest grading instant per problem. Takes the **newest** seen rather than
         * the last line, because the log is append-only and a correction re-appends an older
         * record with the same capture key.
         */
        fun from(records: List<RecordedSubmission>): SubmissionGaps {
            val latest = ConcurrentHashMap<Long, OffsetDateTime>()
            // A line whose timestamp did not parse restores nothing rather than a wrong gap.
            records.forEach { record ->
                record.ts?.let { latest.merge(record.lessonId, it) { a, b -> maxOf(a, b) } }
            }
            return SubmissionGaps(latest)
        }
    }
}
