package com.brokenfinger.tracker.domain

import kotlinx.serialization.Serializable

/**
 * One graded testcase as the domain sees it.
 *
 * Everything but the identifier is nullable, and every null has a measured counterexample
 * (protocol doc §5–§7): timing and memory are absent on runtime error, compile error and
 * timeout, and database gradings never report them at all.
 *
 * [returnedResult] says whether the case reported an output of its own — a database run carries
 * the table its query returned on the result frame, with no message either way (protocol doc §6).
 * It is what tells a query that ran and did not match from a result nothing can be said about
 * (#341). Absent on paths that never report one, and on records written before it existed.
 */
@Serializable
data class TestcaseResult(
    val id: Long,
    val passed: Boolean?,
    val msg: String?,
    val runTime: String?,
    val memorySize: Long?,
    val returnedResult: Boolean? = null,
) {
    /** Anything short of an explicit pass is a failure — an unreported result is not a pass. */
    fun hasFailed(): Boolean = passed != true
}
