package com.brokenfinger.tracker.domain.calc

/**
 * What a step is about, for a reader who sees it apart from its problem. Each field is the newest
 * record's that carries one — the rule `RecordQuery.problem()` and [TallyGroup.bucketKeyOf] already
 * use — so a problem whose early records predate the catalog is still filtered by one [part].
 * Absent when no record carries it.
 */
data class ProblemLabel(val lessonId: Long, val title: String?, val level: Int?, val part: String?)

data class LabelledStep(val problem: ProblemLabel, val step: Transition)

/**
 * Narrows and orders repair steps (dev rules §3). Every argument is optional, and an absent one is
 * not a filter.
 *
 * Applied **after** pairing, so the first step after [since] still starts at the failure that
 * preceded it. [since] bounds the correction — the `to` side — and the answer is newest first by
 * it, because the correction is the event the question is about.
 */
data class RepairStepFilter(val since: Since?, val language: String?, val part: String?, val limit: Int?) {
    fun applied(steps: List<LabelledStep>): List<LabelledStep> {
        val kept = steps.filter(::admits).sortedByDescending { it.step.to.record.ts }
        if (limit == null) return kept
        return kept.take(limit)
    }

    private fun admits(step: LabelledStep): Boolean =
        admitsTime(step) && matches(language, step.step.to.record.language) && matches(part, step.problem.part)

    private fun admitsTime(step: LabelledStep): Boolean = since == null || since.includes(step.step.to.record.ts)

    private fun matches(wanted: String?, actual: String?): Boolean =
        wanted == null || wanted.trim().equals(actual, ignoreCase = true)
}
