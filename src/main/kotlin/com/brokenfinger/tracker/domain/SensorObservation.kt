package com.brokenfinger.tracker.domain

import kotlinx.serialization.Serializable

/**
 * What the browser saw that the grading stream cannot (#120).
 *
 * Kept as its own object rather than flattened into [SubmissionRecord] because the source
 * differs and so does the reliability: everything else in a record came off the wire from
 * Programmers or was computed from it, while this came from an extension that may not be
 * installed, may have been loaded mid-problem, or may be missing entirely because the watch
 * was started by hand. **Absent is the normal case, not a fault**, and a reader has to be
 * able to tell "we did not see" from "it was zero".
 */
@Serializable
data class SensorObservation(
    /**
     * Seconds the problem's tab was actually visible and focused.
     *
     * The record's `elapsedSec` is wall-clock since the problem was first announced (design
     * §5.2), so a problem opened before dinner reads as three hours. This is the number that
     * answers "how long did you work on it", which is what design §6.4's confidence wants.
     */
    val focusedSec: Long,
    /**
     * Whether the problem's questions tab (`/lessons/<id>/questions`) was opened.
     *
     * The measured stand-in for the hint level design §6.4 asks for and nothing supplies:
     * that tab is reachable **before** solving (unlike the other-solutions tab, measured
     * 401 until you pass) and its posts share solutions, so opening it while stuck is
     * seeking help. It says nothing about how much help was taken — only that it was
     * within reach.
     */
    val sawQuestions: Boolean,
) {
    companion object {
        /**
         * Lenient, because these arrive from outside (dev rules §4): a negative or absurd
         * duration is dropped rather than thrown, since losing a verdict over a bad
         * telemetry field would be the wrong trade entirely.
         */
        fun ofReceived(focusedSec: Long?, sawQuestions: Boolean?): SensorObservation? {
            val focused = focusedSec?.takeIf { it >= 0 } ?: return null
            return SensorObservation(focused, sawQuestions ?: false)
        }
    }
}
