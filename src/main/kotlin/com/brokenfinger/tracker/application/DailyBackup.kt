package com.brokenfinger.tracker.application

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference

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
 *
 * **A try that does not count is tried again on a backoff** (#390): a minute later, then twice as long
 * each time, up to an hour ([BackupRetry]). The check runs every minute, and while the day was due every
 * one of them ran git, the records repository's hooks and the content search again, and said why: 1,440
 * times a day for a refusal that stands. The next scheduled backup starts over, and so does a restart.
 */
class DailyBackup(
    private val git: GitSync,
    private val log: BackupLog,
    private val clock: Clock,
    private val at: LocalTime = LocalTime.of(23, 0),
    /**
     * **Required, with no default.** It was `Asia/Seoul`, which is the author's clock standing in
     * for everyone's (#243) — and defaulting to `ZoneId.systemDefault()` instead was worse: a
     * caller that omits it then behaves differently on two machines. `DailyBackupTest` had been
     * inheriting the Seoul default while writing its fixtures in Seoul terms, so it passed here
     * and failed the moment CI's UTC runners supplied a different default.
     *
     * An hour means nothing without a zone. Say which one.
     */
    private val zone: ZoneId,
) {
    /**
     * The scheduled backup this process last said was held back, so a check every minute says it once for
     * each. Kept in memory: a restart says it again.
     */
    private val heldSaidFor = AtomicReference<Instant?>()

    /** The scheduled backup being tried again after a try that did not count, and when it may be (#390). */
    private val retry = AtomicReference<BackupRetry?>()

    /** Backs up when the most recent scheduled hour has not been. Returns whether a backup was recorded. */
    fun runIfDue(): Boolean {
        val due = mostRecentDue()
        if (!isDue(due)) return false
        if (retry.get()?.waits(due, clock.instant()) == true) return false
        return attempted(due)
    }

    // A try that did not count is tried again on the backoff, and one that counted ends it.
    private fun attempted(due: Instant): Boolean {
        val counted = performed(due)
        retry.set(retryAfter(due, counted))
        return counted
    }

    private fun retryAfter(due: Instant, counted: Boolean): BackupRetry? {
        if (counted) return null
        return BackupRetry.after(retry.get(), due, clock.instant())
    }

    /**
     * Reconcile first, then push: a commit that was never made cannot go up, and the whole
     * point of the daily run is the attempts no pass ever pushed.
     *
     * Only a push that actually landed counts as a backup. A failed one leaves the day due, so
     * the next start tries again instead of recording a backup that never left the machine.
     *
     * **And only a day whose records are all committed (#372).** A reconciliation that was refused,
     * waited out the user's merge or could not commit leaves records on this disk alone, and recording
     * the day then told the next check there was nothing left to do. Its answer holds the record, not
     * the push: what is committed still goes up meanwhile. Nothing to reconcile is a reconciliation that
     * succeeded, and a branch with no commit yet and nothing to commit holds nothing to back up — that
     * push answers "nothing to push" (#360), and the day counts.
     *
     * **One exception: a directory git cannot open.** Reconciliation does not see it, commits the rest
     * and answers true, so the day is recorded without what it holds. Holding the day for it would retry
     * all day, on the backoff, for what only the owner can fix. Instead the git adapter says it once a day
     * while it lasts, so every day recorded without it has said so (the review of #389).
     */
    private fun performed(due: Instant): Boolean {
        val reconciled = git.reconcile()
        if (!git.push()) return notPushed()
        if (!reconciled) return heldBack(due)
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

    /**
     * A push answers false both when it failed and when there is nowhere to push, and only the first is a
     * fault (#390). With no remote at all — a documented way to run, which the boot report and the backup
     * schedule say once, at INFO — the day does not count and nothing is said: "could not push" at every
     * check was 1,440 lines a day. The question is the one [BackupReporter] asks to tell the two apart.
     */
    private fun notPushed(): Boolean {
        if (!git.hasRemote()) return false
        return incomplete()
    }

    // Warn rather than throw: a backup that could not go up costs a day of remote history, and
    // the local commits — the record itself — are already durable.
    private fun incomplete(): Boolean {
        logger.warn(NOT_PUSHED)
        return false
    }

    // Why a record stayed uncommitted is said where it happened, and a merge wait only once; the check
    // runs every minute while the day is due, so this process says it once for each scheduled backup.
    private fun heldBack(due: Instant): Boolean {
        if (heldSaidFor.getAndSet(due) != due) logger.warn(HELD_BACK)
        return false
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DailyBackup::class.java)

        const val HELD_BACK =
            "Daily backup held back: records are left uncommitted, and the reconciliation's own warning " +
                "says why; whatever was already committed is pushed. The day stays due and is tried again, " +
                "a minute later at first and at most an hour apart. This process says so once for each " +
                "scheduled backup."

        const val NOT_PUSHED =
            "Daily backup could not push; the day stays due and is tried again, a minute later at first " +
                "and at most an hour apart."
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
