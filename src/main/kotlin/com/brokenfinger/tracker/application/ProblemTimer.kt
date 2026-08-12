package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SensorObservation

/**
 * How long the learner has been on one problem.
 *
 * An outbound port rather than a value the capture path works out for itself (dev rules §1):
 * the broadcast frames say nothing about when the page was opened, so a number invented at
 * settle time would be filed next to a measured verdict as if it had been measured too.
 *
 * The timer starts when a problem is **first** seen and never restarts (design §5.1): reopening
 * the same problem two days later continues the same measurement. Only [startIfAbsent] creates a
 * timer, so a reading never doubles as a start and "never seen" stays distinguishable from "just
 * started".
 *
 * ⚠️ This used to say the question a record answers is *"how long did this problem take"*. It is
 * **wall clock**, and a measured record shows `elapsedSec: 77251` beside `focusedSec: 37` — a
 * half-minute problem on a tab left open overnight (#205). Calendar time from first encounter is
 * a real measure; it is not time on task, and the sensor's `focusedSec` is what answers that.
 */
interface ProblemTimer {
    /** Seconds since this problem was first seen, or 0 when nothing was recorded for it. */
    fun elapsedSecOf(lessonId: Long): Long

    /** Starts the clock for a problem the first time it is seen; a repeat changes nothing. */
    fun startIfAbsent(lessonId: Long)

    /**
     * Keeps what the sensor last reported for this problem (#120).
     *
     * Last-write-wins, because the sensor sends cumulative values on every heartbeat — the
     * newest reading is the complete one, not an increment to add. It lives here rather than
     * in its own store because it is the same kind of thing as the timer: per-problem state
     * that only the browser can tell us, and that has to survive a server restart the same
     * way.
     */
    fun observed(lessonId: Long, observation: SensorObservation)

    /** What the sensor last reported, or null when it never did — no extension, or a manual watch. */
    fun observationOf(lessonId: Long): SensorObservation?
}
