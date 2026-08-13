package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Layer test for the daily backup and its catch-up (design §4.6).
 *
 * Nothing waits for 23:00. The schedule is a comparison against an injected clock, so a
 * machine that slept through the hour, a restart the next morning and an evening that already
 * backed up are all reachable by moving the clock — which is the reason the schedule was
 * written as a comparison rather than as a timer.
 *
 * Git is real: a temp repository, a local bare remote, no network. What "backed up" means is
 * that commits actually arrived at the remote, not that a method was called.
 */
class DailyBackupTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace
    private lateinit var remote: Path

    @BeforeEach
    fun openRecordRepository() {
        repo = GitWorkspace(base)
        remote = repo.withRemote()
    }

    @Test
    fun `an evening past the hour backs up when nothing has yet`() {
        repo.write("log/submissions.jsonl", A_RECORD)

        backup(at = EVENING).runIfDue() shouldBe true

        repo.subjects(at = remote).size shouldBe 2
    }

    /** The attempts a pass never pushed are the whole point of the daily run. */
    @Test
    fun `the backup commits what no pass ever pushed, then pushes it`() {
        repo.write("problems/120804/attempts/001.raw.jsonl", A_RECORD)

        backup(at = EVENING).runIfDue() shouldBe true

        repo.subjects(at = remote).first() shouldBe CommandLineGitSync.RECONCILE_MESSAGE
    }

    @Test
    fun `an evening that already backed up does not back up again`() {
        val backup = backup(at = EVENING)
        backup.runIfDue() shouldBe true

        backup.runIfDue() shouldBe false
    }

    /**
     * The catch-up the design calls for: the laptop was asleep at 23:00, so nothing fired.
     * The next start asks whether the hour has been backed up rather than whether it is now.
     */
    @Test
    fun `a backup the machine slept through runs at the next start`() {
        succeededAt(TWO_NIGHTS_AGO)
        repo.write("log/submissions.jsonl", A_RECORD)

        backup(at = NEXT_MORNING).runIfDue() shouldBe true

        repo.subjects(at = remote).size shouldBe 2
    }

    @Test
    fun `a morning start after the night's backup does not repeat it`() {
        succeededAt(LAST_NIGHT)

        backup(at = NEXT_MORNING).runIfDue() shouldBe false
    }

    @Test
    fun `the evening before the hour is not yet due`() {
        succeededAt(LAST_NIGHT)

        backup(at = BEFORE_THE_HOUR).runIfDue() shouldBe false
    }

    /** The persisted instant is what makes the answer survive the restart it has to survive. */
    @Test
    fun `a restart reads the last backup back off disk rather than starting over`() {
        backup(at = EVENING).runIfDue() shouldBe true

        backup(at = EVENING).runIfDue() shouldBe false
    }

    /**
     * A backup that never left the machine is not a backup. Recording it would skip the day,
     * and the day it skipped is the one whose push failed.
     */
    @Test
    fun `a push that could not land leaves the day due`() {
        val local = GitWorkspace(base.resolve("no-remote"))
        local.write("log/submissions.jsonl", A_RECORD)

        val backup = DailyBackup(CommandLineGitSync(local.root), backupLog(), fixedAt(EVENING), zone = SEOUL)

        backup.runIfDue() shouldBe false

        backupLog().lastSuccessAt() shouldBe null
    }

    // Harness --------------------------------------------------------------------------------

    /**
     * **The zone is named, not inherited.** Every instant below is written in Seoul terms, and
     * this test used to take that zone from `DailyBackup`'s constructor default — so it asserted
     * the default rather than the behaviour, passed on the author's machine, and failed on CI's
     * UTC runners the moment the default changed (#243). A test whose answer depends on where it
     * runs is a test about the machine.
     */
    private fun backup(at: Instant) = DailyBackup(CommandLineGitSync(repo.root), backupLog(), fixedAt(at), zone = SEOUL)

    /** File-backed on purpose: every test above is really asking what a restart would read. */
    private fun backupLog(): BackupLog = FileBackupLog(AtomicStateFile(base.resolve("state/backup.json")))

    private fun succeededAt(instant: Instant) = backupLog().succeededAt(instant)

    private fun fixedAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    private companion object {
        const val A_RECORD = """{"lessonId":120804}"""

        /** Every instant below is a Seoul wall clock, so the backup under test is given that zone. */
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** 2026-08-05, 23:30 in Seoul — half an hour past the scheduled hour. */
        val EVENING: Instant = Instant.parse("2026-08-05T14:30:00Z")

        /** 2026-08-05, 22:00 in Seoul — an hour short of it. */
        val BEFORE_THE_HOUR: Instant = Instant.parse("2026-08-05T13:00:00Z")

        /** 2026-08-06, 09:00 in Seoul. */
        val NEXT_MORNING: Instant = Instant.parse("2026-08-06T00:00:00Z")

        /** 2026-08-05, 23:02 in Seoul — the night before [NEXT_MORNING], backed up. */
        val LAST_NIGHT: Instant = Instant.parse("2026-08-05T14:02:00Z")

        /** 2026-08-04, 23:02 in Seoul — the last backup before a night that was slept through. */
        val TWO_NIGHTS_AGO: Instant = Instant.parse("2026-08-04T14:02:00Z")
    }
}
