package com.brokenfinger.tracker.domain.calc

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
    init {
        require(limit == null || limit > 0) { "limit must be positive" }
        require(language == null || language.isNotBlank()) { "language must not be blank" }
        require(part == null || part.isNotBlank()) { "part must not be blank" }
    }

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
