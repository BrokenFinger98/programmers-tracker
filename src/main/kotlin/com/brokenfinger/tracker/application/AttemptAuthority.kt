package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import java.util.concurrent.ConcurrentHashMap

/**
 * Allocates `attempt` numbers from the one authority that owns them — the submission log
 * ([[decisions/2026-08-05-write-serialization.md]] decision 2).
 *
 * The counter is restored from `log/submissions.jsonl` at startup and advanced in memory
 * afterwards. `attempts/` is never scanned: a second source for the same number is a
 * read-then-write race, and the fix is to delete the second source rather than to guard it.
 *
 * Numbering is **per problem and monotonic across languages**, so the sequence reads as that
 * problem's chronological history (design §5.1). A `run` does not advance it — a run keeps
 * the previous submit's number and writes no attempt file at all.
 *
 * Allocation runs inside the confined writer, but the counters are held in a concurrent map
 * so a stray caller cannot corrupt the sequence — only serialize badly.
 */
class AttemptAuthority private constructor(private val latest: ConcurrentHashMap<Long, Int>) {
    /** The number this grading belongs to, advancing the sequence only for a submit. */
    fun allocate(lessonId: Long, action: GradingAction): Int = when (action) {
        GradingAction.RUN -> latestOf(lessonId)
        GradingAction.SUBMIT -> latest.merge(lessonId, FIRST) { current, _ -> current + 1 }!!
    }

    /** The highest number this problem has reached, or [NONE] when it has never been submitted. */
    fun latestOf(lessonId: Long): Int = latest[lessonId] ?: NONE

    companion object {
        /** No attempt yet — a run before the first submit belongs to no attempt file. */
        const val NONE = 0
        private const val FIRST = 1

        /**
         * Restores the counters from the submission log. Takes the **highest** number seen per
         * problem rather than counting lines, because runs repeat the previous submit's number
         * and a torn line may already have cost us one.
         */
        fun from(records: List<RecordedSubmission>): AttemptAuthority {
            val latest = ConcurrentHashMap<Long, Int>()
            records.forEach { latest.merge(it.lessonId, it.attempt, ::maxOf) }
            return AttemptAuthority(latest)
        }
    }
}
