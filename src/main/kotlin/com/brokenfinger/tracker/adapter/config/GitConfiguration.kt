package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.adapter.store.RecordRepositoryIgnores
import com.brokenfinger.tracker.application.BackupAge
import com.brokenfinger.tracker.application.BackupLog
import com.brokenfinger.tracker.application.BackupReporter
import com.brokenfinger.tracker.application.DailyBackup
import com.brokenfinger.tracker.application.GitSync
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.nio.file.Path
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

/**
 * Assembles the record repository's history: commits ride with the writer, and the backup
 * asks once a minute whether the day's push is still owed
 * ([[decisions/2026-08-06-wire-git-into-the-pipeline]]).
 *
 * Everything here is construction. The hour, the zone and the tick are properties rather than
 * literals because this is distributed publicly and a developer's timezone must not become
 * everyone's (dev rules §9.1).
 */
@Configuration
@EnableScheduling
class GitConfiguration {
    @Bean
    fun gitSync(@Value("\${tracker.record-repo}") recordRepo: String): GitSync =
        CommandLineGitSync(recordRoot(recordRepo))

    /** Beside the repository whose last successful push it records (design §5.1). */
    @Bean
    fun backupLog(@Value("\${tracker.record-repo}") recordRepo: String): BackupLog =
        FileBackupLog.under(recordRoot(recordRepo))

    /**
     * Does its work while being constructed, which is what puts it before the first
     * `git add --all`: reconciliation is an `ApplicationRunner`, and every bean exists before
     * any runner does.
     *
     * It exists because the state moved into the repository (#126) and a `.gitignore` written
     * before that belongs to the user, not to us.
     */
    @Bean
    fun recordRepositoryIgnores(@Value("\${tracker.record-repo}") recordRepo: String): RecordRepositoryIgnores =
        RecordRepositoryIgnores(recordRoot(recordRepo)).also { it.ensure() }

    @Bean
    fun dailyBackup(
        git: GitSync,
        backupLog: BackupLog,
        clock: Clock,
        @Value("\${tracker.backup.at}") at: String,
        @Value("\${tracker.backup.zone}") zone: String,
    ) = DailyBackup(git, backupLog, clock, LocalTime.parse(at), ZoneId.of(zone))

    @Bean
    fun backupSchedule(backup: DailyBackup, reporter: BackupReporter) = BackupSchedule(backup, reporter)

    /**
     * One bean, shared with the startup report: the whole point is remembering what was already
     * said, and two instances would each announce the same change (#185).
     */
    @Bean
    fun backupReporter(backupLog: BackupLog, git: GitSync, clock: Clock) = BackupReporter(backupLog, git, clock)

    private fun recordRoot(recordRepo: String): Path = ConfiguredPath.of(recordRepo)
}

/**
 * Asks the backup whether it is due, on a plain tick rather than a cron expression.
 *
 * A cron entry would state the hour a second time, next to the one [DailyBackup] already owns,
 * and the two would drift. It would also miss the case the design calls out (§4.6): a machine
 * asleep at 23:00 never receives that firing at all. A tick asks a question whose answer
 * survives sleep, restart and a clock that jumped.
 */
class BackupSchedule(private val backup: DailyBackup, private val reporter: BackupReporter) {
    @Scheduled(
        initialDelayString = "\${tracker.backup.check-interval}",
        fixedDelayString = "\${tracker.backup.check-interval}",
    )
    fun tick() {
        backup.runIfDue()
        // After the attempt, and only when the answer changed: the tick fires 1,440 times a day,
        // and a warning repeated that often is one nobody reads (#185).
        reporter.changed()?.let(::announce)
    }

    private fun announce(age: BackupAge) = when (age) {
        // Worth one line. A warning with no matching all-clear leaves the reader unable to tell
        // a fixed problem from an unreported one.
        is BackupAge.Current -> logger.info("The records reached the remote again.")
        is BackupAge.NoRemote ->
            logger.info("The record repository has no remote, so records stay on this machine.")
        is BackupAge.Stale -> warnStale(age)
    }

    private fun warnStale(age: BackupAge) {
        val stale = age as BackupAge.Stale
        if (!stale.everPushed) {
            logger.warn(
                "The record repository has a remote but has never been pushed to — every record " +
                    "this tool has written exists only on this machine.",
            )
            return
        }
        logger.warn(
            "The records have stopped reaching the remote — {} days since the last one landed. " +
                "Everything since then exists only here.",
            stale.days,
        )
    }

    private companion object {
        val logger = org.slf4j.LoggerFactory.getLogger(BackupSchedule::class.java)
    }
}
