package com.brokenfinger.tracker.domain.calc

data class LabelledStep(val problem: ProblemLabel, val step: Transition)

/**
 * What a [RepairStepFilter] answers: the steps it kept, newest first, and how many matched before its
 * limit cut the list. The filter owns the limit, so it owns the count — a reader is never left to
 * guess whether the list it holds is all there is.
 */
data class RepairStepPage(val steps: List<LabelledStep>, val total: Int) {
    init {
        require(total >= steps.size) { "total counts the matches before the limit, never fewer than the page holds" }
    }

    fun isTruncated(): Boolean = total > steps.size
}

/**
 * Narrows and orders repair steps (dev rules §3). Every argument is optional, and an absent one is
 * not a filter.
 *
 * Applied **after** pairing, so the first step after [since] still starts at the failure that
 * preceded it. [since] bounds the correction — the `to` side — and the answer is newest first by
 * it, because the correction is the event the question is about. The answer says how many steps
 * matched before [limit] cut the list ([RepairStepPage.total]), so a cut is never silent.
 */
data class RepairStepFilter(val since: Since?, val language: String?, val part: String?, val limit: Int?) {
    init {
        require(limit == null || limit > 0) { "limit must be positive" }
        require(language == null || language.isNotBlank()) { "language must not be blank" }
        require(part == null || part.isNotBlank()) { "part must not be blank" }
    }

    fun applied(steps: List<LabelledStep>): RepairStepPage {
        val kept = steps.filter(::admits).sortedByDescending { it.step.to.record.ts }
        if (limit == null) return RepairStepPage(kept, kept.size)
        return RepairStepPage(kept.take(limit), kept.size)
    }

    private fun admits(step: LabelledStep): Boolean =
        admitsTime(step) && matches(language, step.step.to.record.language) && matches(part, step.problem.part)

    private fun admitsTime(step: LabelledStep): Boolean = since == null || since.includes(step.step.to.record.ts)

    private fun matches(wanted: String?, actual: String?): Boolean =
        wanted == null || wanted.trim().equals(actual, ignoreCase = true)
}
