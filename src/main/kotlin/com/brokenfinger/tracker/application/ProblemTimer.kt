package com.brokenfinger.tracker.application

/**
 * How long the learner has been on one problem.
 *
 * An outbound port rather than a value the capture path works out for itself (dev rules §1):
 * the broadcast frames say nothing about when the page was opened, so a number invented at
 * settle time would be filed next to a measured verdict as if it had been measured too.
 *
 * The timer starts when a problem is **first** seen and never restarts (design §5.1): reopening
 * the same problem two days later continues the same measurement, because the question a record
 * answers is "how long did this problem take", not "how long was this tab open". Only
 * [startIfAbsent] creates a timer, so a reading never doubles as a start and "never seen" stays
 * distinguishable from "just started".
 */
interface ProblemTimer {
    /** Seconds since this problem was first seen, or 0 when nothing was recorded for it. */
    fun elapsedSecOf(lessonId: Long): Long

    /** Starts the clock for a problem the first time it is seen; a repeat changes nothing. */
    fun startIfAbsent(lessonId: Long)
}
