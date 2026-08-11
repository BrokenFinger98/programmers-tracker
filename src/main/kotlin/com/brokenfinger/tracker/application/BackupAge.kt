package com.brokenfinger.tracker.application

import java.time.Duration
import java.time.Instant

/**
 * How long the records have been sitting on one disk, and whether that is a fault.
 *
 * `BackupLog.lastSuccessAt()` has always known this and nothing read it except the "is the
 * backup due" check, so a push that failed every day for a week produced one warning on the
 * first day and silence afterwards (#183).
 *
 * Pure by construction (dev rules §3): two facts and a clock in, a verdict out.
 */
sealed interface BackupAge {
    /** Pushed recently enough that nothing needs saying. */
    data object Current : BackupAge

    /**
     * No remote, so the records have never left and were never going to.
     *
     * **Not a fault.** The README says pushing needs credentials the tool cannot invent, and
     * that without them it still captures and still commits — a supported way to run it. Said
     * once, as a fact about how this copy is set up, never as an alarm.
     */
    data object NoRemote : BackupAge

    /** A remote exists and the last successful push is old, or there has never been one. */
    data class Stale(val days: Long, val everPushed: Boolean) : BackupAge

    companion object {
        /**
         * A day of solving is the unit that matters — a laptop dying with one day unpushed is a
         * bad afternoon, a week unpushed is the loss this tool exists to prevent. Two days is
         * chosen so an ordinary weekend of not opening the machine does not raise it.
         */
        val TOLERANCE: Duration = Duration.ofDays(2)

        fun of(lastSuccessAt: Instant?, hasRemote: Boolean, now: Instant): BackupAge {
            if (!hasRemote) return NoRemote
            val since = lastSuccessAt ?: return Stale(days = 0, everPushed = false)
            val elapsed = Duration.between(since, now)
            if (elapsed < TOLERANCE) return Current
            return Stale(days = elapsed.toDays(), everPushed = true)
        }
    }
}
