package com.brokenfinger.tracker.application

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The daily backup push (design §4.6).
 *
 * A pass pushes, so a problem that is never solved is never pushed — and those are exactly the
 * attempts the record exists for. Once a day the branch goes up regardless of whether anything
 * passed.
 *
 * **A missed run is caught up, not skipped.** The machine is a laptop that is asleep at 23:00
 * more often than not, so a scheduler that only fires on the hour would lose whole days. The
 * question this class asks is not "is it 23:00 now" but "has the most recent 23:00 been backed
 * up yet", which the next start answers just as well as the hour itself. That makes the
 * schedule a comparison against an injected [Clock] rather than a timer, so every catch-up
 * case is testable without waiting for one.
 *
 * The last success is persisted rather than held in memory for the same reason: the process
 * restarting is the normal case, not the exceptional one.
 */
class DailyBackup(
    private val git: GitSync,
    private val log: BackupLog,
    private val clock: Clock,
    private val at: LocalTime = LocalTime.of(23, 0),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    /** Backs up when the most recent scheduled hour has not been. Returns whether it ran. */
    fun runIfDue(): Boolean {
        val due = mostRecentDue()
        if (!isDue(due)) return false
        return performed()
    }

    /**
     * Reconcile first, then push: a commit that was never made cannot go up, and the whole
     * point of the daily run is the attempts no pass ever pushed.
     *
     * Only a push that actually landed counts as a backup. A failed one leaves the day due, so
     * the next start tries again instead of recording a backup that never left the machine.
     */
    private fun performed(): Boolean {
        git.reconcile()
        if (!git.push()) return incomplete()
        log.succeededAt(clock.instant())
        logger.info("Daily backup pushed the record repository")
        return true
    }

    private fun isDue(due: Instant): Boolean {
        val last = log.lastSuccessAt() ?: return true
        return last.isBefore(due)
    }

    /** The scheduled instant at or before now — today's if it has passed, else yesterday's. */
    private fun mostRecentDue(): Instant {
        val now = ZonedDateTime.ofInstant(clock.instant(), zone)
        val today = now.with(at)
        if (today.isAfter(now)) return today.minusDays(1).toInstant()
        return today.toInstant()
    }

    // Warn rather than throw: a backup that could not go up costs a day of remote history, and
    // the local commits — the record itself — are already durable.
    private fun incomplete(): Boolean {
        logger.warn("Daily backup could not push; the day stays due and the next start retries")
        return false
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DailyBackup::class.java)
    }
}

/**
 * When the last backup succeeded — an outbound port (dev rules §1), because the answer has to
 * survive the process that produced it.
 *
 * Null means "never", which makes a fresh install due immediately. That is the safe direction:
 * a backup too many costs a no-op push, a backup too few costs a day of unpushed attempts.
 */
interface BackupLog {
    fun lastSuccessAt(): Instant?

    fun succeededAt(instant: Instant)
}
