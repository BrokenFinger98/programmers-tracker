package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.calc.KeptCode

/**
 * Reads back the code kept beside the records — each run's line in `runs.jsonl`, each submit's
 * attempt file — for repair steps (spec 2026-10-07 §4.3).
 *
 * An outbound port for the reason [ProblemStatements] is one: the code is files, this layer knows
 * no filesystem, and the read side must hold nothing that can write
 * ([[decisions/2026-08-06-mcp-read-slice]]). Never throws: code that cannot be read is code that
 * was not kept, and the step it belonged to says so.
 */
interface GradingCodes {
    /** Every kept run of one problem, by `SubmissionRecord.recordId()`. Empty when none was kept. */
    fun runs(lessonId: Long, title: String?): Map<String, KeptCode>

    /** A submit's code, from the path its record carries. Null when gone, unreadable or outside `problems/`. */
    fun submitted(codePath: String): String?

    companion object {
        /** Keeps nothing — what a repository that never kept code answers. */
        val NONE: GradingCodes = object : GradingCodes {
            override fun runs(lessonId: Long, title: String?): Map<String, KeptCode> = emptyMap()

            override fun submitted(codePath: String): String? = null
        }
    }
}
