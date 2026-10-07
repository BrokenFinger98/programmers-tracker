package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.SubmissionRecord

/**
 * What a step is about, for a reader who sees it apart from its problem. Each field is the newest
 * record's that carries one — the rule `RecordQuery.problem()` and [TallyGroup.bucketKeyOf] already
 * use — so a problem whose early records predate the catalog is still filtered by one [part].
 * Absent when no record carries it.
 */
data class ProblemLabel(val lessonId: Long, val title: String?, val level: Int?, val part: String?) {
    companion object {
        /**
         * The label of one problem's [records], in any order. Records that share a timestamp keep
         * the order they were handed in, so a caller holding `RecordQuery.history()` (newest first)
         * gets exactly what `get_problem` shows.
         */
        fun of(records: List<SubmissionRecord>): ProblemLabel {
            require(records.map { it.lessonId }.distinct().size == 1) { "a label is one problem's" }
            return ProblemLabel(
                lessonId = records.first().lessonId,
                title = newestCarrying(records) { it.title.takeIf(String::isNotBlank) },
                level = newestCarrying(records) { it.level },
                part = newestCarrying(records) { it.part?.takeIf(String::isNotBlank) },
            )
        }

        /** The rule itself: the newest record whose [field] is present. Shared with [TallyGroup]. */
        internal fun <T> newestCarrying(records: List<SubmissionRecord>, field: (SubmissionRecord) -> T?): T? =
            records.sortedByDescending { it.ts }.firstNotNullOfOrNull(field)
    }
}
