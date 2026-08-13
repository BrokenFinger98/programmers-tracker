package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.git.GithubRemote
import com.brokenfinger.tracker.adapter.git.GithubToken
import com.brokenfinger.tracker.adapter.git.RecordRepositoryInit
import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.adapter.store.RecordRepositoryIgnores
import com.brokenfinger.tracker.adapter.store.VaultDashboard
import com.brokenfinger.tracker.application.BackupAge
import com.brokenfinger.tracker.application.BackupLog
import com.brokenfinger.tracker.application.BackupReporter
import com.brokenfinger.tracker.application.DailyBackup
import com.brokenfinger.tracker.application.GitSync
import org.slf4j.LoggerFactory
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
 * everyone's (dev rules §9.1) — a rule this file stated and its own `Asia/Seoul` default broke
 * until #243. The zone now falls back to the process's own clock, which is `TZ`.
 */
@Configuration
@EnableScheduling
class GitConfiguration {
    /**
     * Constructed first and depended on by [gitSync], so the repository exists before the lazy
     * is-a-repository answer is ever computed (#258). The three commands bootstrap.md §2 used to
     * ask of the user happen here, or not at all — an existing repository is untouched.
     */
    @Bean
    fun recordRepositoryInit(@Value("\${tracker.record-repo}") recordRepo: String): RecordRepositoryInit =
        RecordRepositoryInit(recordRoot(recordRepo)).also { it.ensure() }

    /**
     * After [recordRepositoryInit] (it needs a repository to wire) and before any runner (the
     * startup backup is what carries the first push). A blank token is the common case and a
     * complete no-op; an existing origin of any kind is never touched (#258).
     */
    @Bean
    fun githubRemote(
        init: RecordRepositoryInit,
        @Value("\${tracker.record-repo}") recordRepo: String,
        @Value("\${tracker.github.token:}") token: String,
    ): GithubRemote =
        GithubRemote(recordRoot(recordRepo), token.trim().ifEmpty { null }?.let(::GithubToken)).also { it.ensure() }

    @Bean
    fun gitSync(init: RecordRepositoryInit, @Value("\${tracker.record-repo}") recordRepo: String): GitSync =
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

    /**
     * Also at construction, and for the same reason: it must land before reconciliation's first
     * `git add --all`, so the dashboard arrives in the same commit as the records rather than
     * trailing a boot behind.
     *
     * Written only when absent — a `.base` is a query the reader owns once they touch it, not
     * derived data to regenerate (#254).
     */
    @Bean
    fun vaultDashboard(@Value("\${tracker.record-repo}") recordRepo: String): VaultDashboard =
        VaultDashboard(recordRoot(recordRepo)).also { it.ensure() }

    @Bean
    fun dailyBackup(
        git: GitSync,
        backupLog: BackupLog,
        clock: Clock,
        @Value("\${tracker.backup.at}") at: String,
        @Value("\${tracker.backup.zone}") zone: String,
    ) = DailyBackup(git, backupLog, clock, LocalTime.parse(at), zoneOf(zone))

    /**
     * The backup's clock is the process's clock unless something says otherwise, and the process
     * takes its clock from `TZ`. One knob, so the hour a record is stamped with and the hour the
     * backup fires at cannot disagree about what day it is (#243).
     *
     * Announced rather than assumed: a container with no `TZ` runs in UTC, and an attempt history
     * rendering nine hours off looks like a capture bug rather than a setting.
     */
    private fun zoneOf(zone: String): ZoneId = ConfiguredZone.of(zone).also {
        logger.info("Records are stamped in {}, and the daily backup keeps that clock", it)
    }

    @Bean
    fun backupSchedule(backup: DailyBackup, reporter: BackupReporter) = BackupSchedule(backup, reporter)

    /**
     * One bean, shared with the startup report: the whole point is remembering what was already
     * said, and two instances would each announce the same change (#185).
     */
    @Bean
    fun backupReporter(backupLog: BackupLog, git: GitSync, clock: Clock) = BackupReporter(backupLog, git, clock)

    private fun recordRoot(recordRepo: String): Path = ConfiguredPath.of(recordRepo)

    private companion object {
        val logger = LoggerFactory.getLogger(GitConfiguration::class.java)
    }
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
        val logger = LoggerFactory.getLogger(BackupSchedule::class.java)
    }
}
