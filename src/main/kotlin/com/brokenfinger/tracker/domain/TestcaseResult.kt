package com.brokenfinger.tracker.domain

/**
 * One graded testcase as the domain sees it.
 *
 * Everything but the identifier is nullable, and every null has a measured counterexample
 * (protocol doc §5–§7): timing and memory are absent on runtime error, compile error and
 * timeout, and database gradings never report them at all.
 */
data class TestcaseResult(
    val id: Long,
    val passed: Boolean?,
    val msg: String?,
    val runTime: String?,
    val memorySize: Long?,
) {
    /** Anything short of an explicit pass is a failure — an unreported result is not a pass. */
    fun hasFailed(): Boolean = passed != true
}
