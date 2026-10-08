package com.brokenfinger.tracker.application

import java.time.Duration
import java.time.Instant

/**
 * When a scheduled backup that did not count is tried again (#390): a minute after the first try that
 * failed, then twice as long after each further one, up to an hour. The next scheduled backup is a new
 * one and starts over.
 *
 * Without it the check that runs every minute tried again at every one while the day was due — git, the
 * records repository's hooks and the content search, 1,440 times a day for a refusal that stands, each
 * said again. With it a standing refusal is tried seven times in its first two hours and hourly after
 * that, and a fix is picked up within the hour.
 *
 * Pure (dev rules §3): instants in, "wait or try" out. Kept in memory, so a restart tries at once.
 */
internal class BackupRetry private constructor(
    private val due: Instant,
    private val failures: Int,
    private val next: Instant,
) {
    /** Whether a check at [now], for the backup scheduled at [due], waits rather than tries again. */
    fun waits(due: Instant, now: Instant): Boolean = due == this.due && now.isBefore(next)

    // How many tries for [due] have failed, counting this one; none when it is another scheduled backup.
    private fun failuresFor(due: Instant): Int = failures.takeIf { this.due == due } ?: 0

    companion object {
        /** The wait after the first failure: the next check, at the schedule's own pace. */
        val FIRST: Duration = Duration.ofMinutes(1)

        /** The longest wait, so that a fix is picked up within the hour it is made. */
        val LONGEST: Duration = Duration.ofHours(1)

        /**
         * The doublings that take [FIRST] to [LONGEST] or past it, counted from the two rather than written
         * down beside them; the shift stops there, so it never overflows.
         */
        private val DOUBLINGS: Int = generateSequence(FIRST) { it.multipliedBy(2) }.takeWhile { it < LONGEST }.count()

        /** The retry after a try for [due] failed at [failedAt], counting on from [previous] for the same [due]. */
        fun of(previous: BackupRetry?, due: Instant, failedAt: Instant): BackupRetry {
            val failures = (previous?.failuresFor(due) ?: 0) + 1
            return BackupRetry(due, failures, failedAt.plus(waitAfter(failures)))
        }

        private fun waitAfter(failures: Int): Duration =
            FIRST.multipliedBy(1L shl minOf(failures - 1, DOUBLINGS)).coerceAtMost(LONGEST)
    }
}
