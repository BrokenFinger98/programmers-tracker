package com.brokenfinger.tracker.application

import org.slf4j.LoggerFactory

/**
 * Everything an earlier run may have left unfinished, done once at boot
 * ([[decisions/2026-08-06-wire-git-into-the-pipeline]]).
 *
 * Four recoveries in one place because they are one question — *what did the last process not
 * get to?* — and because their order matters: sessions become records, records get their code,
 * records become commits, commits go up. Run them apart and a boot can commit a tree the raw
 * sessions were about to add to.
 *
 * Every step is idempotent, so this is safe to run on a boot that has nothing to recover and
 * safe to run again after one that failed halfway.
 */
class StartupReconciliation(
    private val sessions: RawSessionReconciler,
    private val raw: RawSessionLog,
    private val backupReporter: BackupReporter,
    private val attachment: CodeAttachment,
    private val git: GitSync,
    private val backup: DailyBackup,
) {
    suspend fun run() {
        logger.info("Startup reconciliation: {}", sessions.reconcile())
        reportOrphans()
        // Between the sessions and the commit, not after it. Whatever the pass above recovered
        // is written `codePending`, and code attached after `git.reconcile` would sit
        // uncommitted until the next capture happened to sweep it up.
        logger.info("Startup code attachment: {}", attachment.attachPending())
        refreshTagMap()
        // Records an earlier run wrote but never committed — a git failure at capture time
        // leaves exactly this, and "commit whatever is uncommitted" is how it heals.
        git.reconcile()
        backup.runIfDue()
        // After the attempt, not before: a boot that successfully pushes should not also warn
        // that it had not pushed.
        reportBackupAge()
    }

    /**
     * The vault's tag map, rewritten whole at every boot (#229).
     *
     * Derived and regenerable, so a failure here is logged and does not propagate — the records
     * are not ours to lose over a note. It runs after the attachment pass so that whatever
     * reconciliation just recovered is already counted.
     */
    private fun refreshTagMap() {
        runCatching { attachment.refreshTagMap() }
            .onFailure { logger.warn("The tag map could not be written; the records are unaffected", it) }
    }

    /**
     * Says how long the records have been sitting on one disk, at every boot rather than on the
     * day a push failed (#183).
     *
     * `BackupLog` has always known this and only the "is a backup due" check read it, so a push
     * failing every day for a week produced one warning on the first day. The tool's whole claim
     * is that failures stop being lost; a laptop that dies loses the replacement too.
     */
    /**
     * Separated from the logging so a test can assert the verdict rather than scrape a logger.
     * A check whose only output is a log line is one that gets asserted on by nobody.
     *
     * Unconditional, unlike the tick's: a boot is a new context and the operator has seen
     * nothing yet, so the current state is stated whatever it was last time (#185).
     */
    internal fun backupAge(): BackupAge = backupReporter.current()

    private fun reportBackupAge() {
        when (val age = backupAge()) {
            is BackupAge.Current -> Unit
            // Stated, not warned. Running without a remote is supported (README) and an alarm
            // for a deliberate choice is how a real alarm gets ignored.
            is BackupAge.NoRemote ->
                logger.info(
                    "The record repository has no remote, so records stay on this machine. " +
                        "That is a supported way to run the tool — see docs/bootstrap.md to push them somewhere.",
                )
            is BackupAge.Stale -> warnStale(age)
        }
    }

    private fun warnStale(age: BackupAge.Stale) {
        if (!age.everPushed) {
            logger.warn(
                "The record repository has a remote but has never been pushed to — every record " +
                    "this tool has written exists only on this machine. Check the push credentials.",
            )
            return
        }
        logger.warn(
            "The records last left this machine {} days ago. Everything since then exists only here.",
            age.days,
        )
    }

    /**
     * Announced at every boot, not once when it happened. Orphaned frames are the part of the
     * history that will never become records, and a warning that scrolled past on the day is
     * indistinguishable from a complete record afterwards (#169).
     *
     * Reported, never repaired. The missing `start` carries the testcase ids and the problem's
     * examples, and pairing a stretch of these frames with the attempt it belongs to would be
     * inference — the file is per-lesson and append-only, so several gradings sit in it end to
     * end with nothing between them.
     */
    private fun reportOrphans() {
        val orphans = raw.orphans()
        if (orphans.isEmpty()) return
        logger.warn(
            "{} lesson(s) have frames that belong to no grading and will never become records: {}. " +
                "Read them at {} — they are evidence, not a work list",
            orphans.size,
            orphans.joinToString { "lesson ${it.lessonId} (${it.frames} frames)" },
            orphans.first().path.parent,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(StartupReconciliation::class.java)
    }
}
