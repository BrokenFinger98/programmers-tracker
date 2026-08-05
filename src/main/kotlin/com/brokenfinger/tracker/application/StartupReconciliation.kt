package com.brokenfinger.tracker.application

import org.slf4j.LoggerFactory

/**
 * Everything an earlier run may have left unfinished, done once at boot
 * ([[decisions/2026-08-06-wire-git-into-the-pipeline]]).
 *
 * Three recoveries in one place because they are one question — *what did the last process not
 * get to?* — and because their order matters: sessions become records, records become commits,
 * commits go up. Run them apart and a boot can commit a tree the raw sessions were about to
 * add to.
 *
 * Every step is idempotent, so this is safe to run on a boot that has nothing to recover and
 * safe to run again after one that failed halfway.
 */
class StartupReconciliation(
    private val sessions: RawSessionReconciler,
    private val git: GitSync,
    private val backup: DailyBackup,
) {
    suspend fun run() {
        logger.info("Startup reconciliation: {}", sessions.reconcile())
        // Records an earlier run wrote but never committed — a git failure at capture time
        // leaves exactly this, and "commit whatever is uncommitted" is how it heals.
        git.reconcile()
        backup.runIfDue()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(StartupReconciliation::class.java)
    }
}
