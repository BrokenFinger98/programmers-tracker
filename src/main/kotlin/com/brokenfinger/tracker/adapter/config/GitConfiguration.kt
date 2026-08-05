package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.application.BackupLog
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

    @Bean
    fun backupLog(@Value("\${tracker.backup.state-file}") stateFile: String): BackupLog =
        FileBackupLog(AtomicStateFile(Path.of(stateFile)))

    @Bean
    fun dailyBackup(
        git: GitSync,
        backupLog: BackupLog,
        clock: Clock,
        @Value("\${tracker.backup.at}") at: String,
        @Value("\${tracker.backup.zone}") zone: String,
    ) = DailyBackup(git, backupLog, clock, LocalTime.parse(at), ZoneId.of(zone))

    @Bean
    fun backupSchedule(backup: DailyBackup) = BackupSchedule(backup)

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
class BackupSchedule(private val backup: DailyBackup) {
    @Scheduled(
        initialDelayString = "\${tracker.backup.check-interval}",
        fixedDelayString = "\${tracker.backup.check-interval}",
    )
    fun tick() {
        backup.runIfDue()
    }
}
