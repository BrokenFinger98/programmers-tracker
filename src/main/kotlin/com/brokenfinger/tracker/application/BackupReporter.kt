package com.brokenfinger.tracker.application

import java.time.Clock

/**
 * Says how long the records have been on one disk, once per change rather than once per look.
 *
 * #183 put the report at startup, which leaves the machine that is never restarted: a deploy key
 * that expires on Tuesday produces a failing push every night, the same line each time, and
 * nothing that says the gap is now a week (#185).
 *
 * The tick asks every minute, so the state cannot be announced on every look — 1,440 identical
 * warnings a day is a warning nobody reads, which is the failure the `NoRemote` case was
 * introduced to avoid in the first place.
 *
 * **Announced on change of kind, and that includes changing back.** "Pushed again" is worth one
 * line: a warning with no matching all-clear leaves the reader unable to tell a fixed problem
 * from an unreported one.
 *
 * The days a `Stale` has lasted are deliberately **not** part of the comparison. Counting them
 * would fire once a day forever, which is the noise this exists to avoid; the boot report is
 * where the current number is stated.
 */
class BackupReporter(private val backupLog: BackupLog, private val git: GitSync, private val clock: Clock) {
    private val lock = Any()
    private var announced: String? = null

    /** The current age, always — for a context that has seen nothing yet, such as a boot. */
    fun current(): BackupAge = BackupAge.of(backupLog.lastSuccessAt(), git.hasRemote(), clock.instant()).also {
        synchronized(lock) { announced = kindOf(it) }
    }

    /** The current age when it differs in kind from the last one announced, otherwise null. */
    fun changed(): BackupAge? {
        val age = BackupAge.of(backupLog.lastSuccessAt(), git.hasRemote(), clock.instant())
        synchronized(lock) {
            if (kindOf(age) == announced) return null
            announced = kindOf(age)
        }
        return age
    }

    // Kind, not value: a Stale that grew from 9 days to 10 is the same news.
    private fun kindOf(age: BackupAge): String = when (age) {
        is BackupAge.Current -> "current"
        is BackupAge.NoRemote -> "no-remote"
        is BackupAge.Stale -> if (age.everPushed) "stale" else "never"
    }
}
