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
        // Records an earlier run wrote but never committed — a git failure at capture time
        // leaves exactly this, and "commit whatever is uncommitted" is how it heals.
        git.reconcile()
        backup.runIfDue()
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
