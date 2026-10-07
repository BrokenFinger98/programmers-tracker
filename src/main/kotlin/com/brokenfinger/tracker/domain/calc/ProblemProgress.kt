package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict

/**
 * How the problems in one `stats` bucket went — counts of problems, never a verdict on them
 * (spec 2026-10-07 §4.3, [[decisions/2026-08-12-the-server-counts-and-names-nothing]]).
 *
 * **Problems, not (problem, language) pairs**: a part or a level is a property of the problem, and
 * `stats(groupBy=problem)` already counts across languages. A run in any language before the first
 * passing submit is a run before the pass.
 *
 * [passedFirstSubmit] counts first submits that resolved PASS; an unresolved first submit is not one.
 *
 * [runsBeforePass] is a median, so a `Double`, and **absent when no problem in the bucket passed** —
 * there is nothing to take a median of, which is not the same as zero runs.
 */
data class ProblemProgress(
    val attempted: Int,
    val passed: Int,
    val passedFirstSubmit: Int,
    val runsBeforePass: Double?,
) {
    companion object {
        /**
         * Progress per bucket of [group], over every record (runs included), each problem in the
         * bucket of its newest known value ([TallyGroup.bucketKeyOf]). Empty for a grouping that
         * does not count problems.
         */
        fun perBucket(records: List<SubmissionRecord>, group: TallyGroup): Map<String?, ProblemProgress> {
            if (!group.countsProblems()) return emptyMap()
            return records.groupBy(group.bucketKeyOf(records)).mapValues { (_, grouped) -> of(grouped) }
        }

        /** [records] is one bucket's gradings, runs included; only problems with a submit count. */
        fun of(records: List<SubmissionRecord>): ProblemProgress {
            val problems = records.groupBy { it.lessonId }.values
                .filter { it.any(SubmissionRecord::isSubmission) }
            return ProblemProgress(
                attempted = problems.size,
                passed = problems.count { firstPass(it) != null },
                passedFirstSubmit = problems.count { firstSubmit(it)?.verdict == Verdict.PASS },
                runsBeforePass = median(problems.mapNotNull(::runsBeforePass)),
            )
        }

        private fun firstSubmit(records: List<SubmissionRecord>): SubmissionRecord? =
            records.filter { it.isSubmission() }.minByOrNull { it.ts }

        private fun firstPass(records: List<SubmissionRecord>): SubmissionRecord? =
            records.filter { it.isSubmission() && it.verdict == Verdict.PASS }.minByOrNull { it.ts }

        private fun runsBeforePass(records: List<SubmissionRecord>): Int? {
            val pass = firstPass(records) ?: return null
            return records.count { !it.isSubmission() && it.ts.isBefore(pass.ts) }
        }

        private fun median(values: List<Int>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            if (sorted.size % 2 == 1) return sorted[middle].toDouble()
            return (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
