package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.SensorObservation
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aFrameReader
import com.brokenfinger.tracker.support.fixtures.aQuietGitSync
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Layer test for what a boot recovers (dev rules §6.1).
 *
 * Real files, a real git repository and a real bare remote — this code only ever runs after a
 * crash or an outage, so a doubled-out version of it would prove nothing about the state one
 * actually leaves. The clock is injected, so the slept-through backup needs no waiting.
 */
class StartupReconciliationTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun openRecordRepository() {
        repo = GitWorkspace(base)
    }

    @Test
    fun `a record an earlier run left uncommitted is committed at startup`() {
        repo.write("log/submissions.jsonl", A_RECORD)

        startup()

        repo.subjects().single() shouldBe CommandLineGitSync.RECONCILE_MESSAGE
        repo.filesInHead() shouldContainExactly listOf("log/submissions.jsonl")
    }

    /** Sessions become records before records become commits, or the commit misses them. */
    @Test
    fun `an orphaned raw session becomes a record, and that record is committed`() {
        stage(LESSON_ID, FixtureLoader.broadcastLines("algorithm-pass.jsonl"))

        startup()

        repo.filesInHead() shouldContain "log/submissions.jsonl"
        repo.subjects().size shouldBe 1
    }

    @Test
    fun `a boot with nothing left behind commits nothing`() {
        startup()

        repo.subjects() shouldContainExactly emptyList()
    }

    @Test
    fun `startup is safe to run again and again`() {
        repo.write("log/submissions.jsonl", A_RECORD)

        repeat(3) { startup() }

        repo.subjects().size shouldBe 1
    }

    @Test
    fun `a backup the machine slept through is performed at startup`() {
        val remote = repo.withRemote()
        repo.write("log/submissions.jsonl", A_RECORD)

        startup()

        repo.subjects(at = remote).size shouldBe 2
    }

    // How long the records have been on one disk (#183) -------------------------------------

    /**
     * The wiring, asserted on a verdict rather than by scraping a logger — a check whose only
     * output is a log line is one nobody asserts on.
     *
     * A repository nobody gave a remote is the default state of a fresh checkout, and it is a
     * supported way to run the tool. It must never read as a fault.
     */
    @Test
    fun `a repository with no remote is not a fault`() {
        reconciliation().backupAge() shouldBe BackupAge.NoRemote
    }

    @Test
    fun `a recent push says nothing`() {
        repo.withRemote()
        backupLog().succeededAt(clock().instant())

        reconciliation().backupAge() shouldBe BackupAge.Current
    }

    @Test
    fun `records that have not left for days say how many`() {
        repo.withRemote()
        backupLog().succeededAt(clock().instant().minus(Duration.ofDays(9)))

        reconciliation().backupAge() shouldBe BackupAge.Stale(days = 9, everPushed = true)
    }

    /**
     * A remote that has never been pushed to is the shape of a deploy key that never worked,
     * and it asks the user for something different from a stale one.
     */
    @Test
    fun `a remote that was never pushed to is reported as never`() {
        repo.withRemote()

        reconciliation().backupAge() shouldBe BackupAge.Stale(days = 0, everPushed = false)
    }

    // Harness --------------------------------------------------------------------------------

    private fun startup() = runBlocking { reconciliation().run() }

    private fun reconciliation(): StartupReconciliation {
        val git = CommandLineGitSync(repo.root)
        return StartupReconciliation(
            rawSessions(),
            rawLog(),
            backupLog(),
            clock(),
            attachment(),
            git,
            DailyBackup(git, backupLog(), clock()),
        )
    }

    /**
     * A real attachment over a fetcher that can never reach the page, because these tests are
     * about the git half of the boot. It still runs for real — a pass that silently threw would
     * take the commit and the backup down with it, and that is precisely what this class exists
     * to order correctly.
     */
    private fun attachment(): CodeAttachment {
        val store = JsonlRecordStore.under(repo.root)
        return CodeAttachment(
            fetcher = { _, _ -> CodeFetch.Unavailable("no page source in this test") },
            store = store,
            artifacts = FileDerivedArtifacts(repo.root, store),
            writerDispatcher = Dispatchers.Unconfined,
        )
    }

    /**
     * The writer's own commit is switched off here. What a git failure leaves behind is a
     * record with no commit, and that is exactly the state these tests hand the boot.
     */
    private fun rawLog(): RawSessionLog = FileRawSessionLog(rawDirectory(), clock())

    private fun rawSessions(): RawSessionReconciler {
        val rawLog = FileRawSessionLog(rawDirectory(), clock())
        val layout = RecordLayout(repo.root)
        val writer = RecordWriter.of(
            store = JsonlRecordStore.under(repo.root),
            rawLog = rawLog,
            rawAttemptPath = AttemptRawPath(layout::rawAttemptFile),
            recordRoot = repo.root,
            git = aQuietGitSync(),
            submissionLog = layout.submissionLog(),
            clock = clock(),
            writerDispatcher = Dispatchers.Unconfined,
        )
        return RawSessionReconciler(rawLog, writer, StoppedTimer, aFrameReader(), anEmptyCatalog(), clock())
    }

    private fun backupLog() = FileBackupLog(AtomicStateFile(base.resolve("state/backup.json")))

    /** 2026-08-06, 09:00 in Seoul — a morning start after a night nothing backed up. */
    private fun clock(): Clock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC)

    /**
     * A directory of its own, which is all reconciliation cares about: it walks whatever raw
     * log it is handed. Production puts that under the record repository (design §5.1, #126)
     * and `RecordWriterGitTest` is where the consequence — that it never gets committed — is
     * pinned against real git.
     */
    private fun rawDirectory(): Path = base.resolve("raw")

    private fun stage(lessonId: Long, frames: List<String>) {
        val log = FileRawSessionLog(rawDirectory(), clock())
        val session = log.start(lessonId)
        frames.forEach { log.append(session, it) }
    }

    private companion object {
        const val LESSON_ID = 120804L
        const val A_RECORD = """{"lessonId":120804}"""
    }
}

/** Time on a problem is not what this test measures. */
private object StoppedTimer : ProblemTimer {
    override fun elapsedSecOf(lessonId: Long): Long = 0

    override fun startIfAbsent(lessonId: Long) = Unit

    override fun observed(lessonId: Long, observation: SensorObservation) {
        observations[lessonId] = observation
    }

    override fun observationOf(lessonId: Long): SensorObservation? = observations[lessonId]

    val observations = mutableMapOf<Long, SensorObservation>()
}
