package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.adapter.store.SeedLedger
import com.brokenfinger.tracker.adapter.store.VaultDashboard
import com.brokenfinger.tracker.domain.SensorObservation
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aFrameReader
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aQuietGitSync
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.git.GitWorkspace
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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

    /** With the front page the boot writes beside it (#414): a repository that had none gets one. */
    @Test
    fun `a record an earlier run left uncommitted is committed at startup`() {
        repo.write("log/submissions.jsonl", A_RECORD)

        startup()

        repo.subjects().single() shouldBe CommandLineGitSync.RECONCILE_MESSAGE
        repo.filesInHead() shouldContainExactly listOf("README.md", "log/submissions.jsonl")
    }

    /** Sessions become records before records become commits, or the commit misses them. */
    @Test
    fun `an orphaned raw session becomes a record, and that record is committed`() {
        stage(LESSON_ID, FixtureLoader.broadcastLines("algorithm-pass.jsonl"))

        startup()

        repo.filesInHead() shouldContain "log/submissions.jsonl"
        repo.subjects().size shouldBe 1
    }

    /**
     * A log a link stands in for is refused rather than read (#387), and the boot is not where that ends. The startup
     * runner catches nothing, so each reader of the log at boot says it could not read it and goes on, and the server
     * stays up to keep every grading's frames until the link is gone. What the link leads to is neither read as the
     * history nor changed.
     */
    @Test
    fun `a submission log that is a link is refused at boot, said, and the boot goes on`() {
        assumeTrue(canPlantLinksIn(base), "this test makes symbolic links")
        val elsewhere = Files.createDirectories(base.resolve("elsewhere")).resolve("submissions.jsonl")
        Files.writeString(elsewhere, "$A_RECORD\n")
        aLink(repo.root.resolve("log/submissions.jsonl"), elsewhere)

        val heard = warningsWhile(CodeAttachment::class) { startup() }

        heard.single() shouldContain "could not be read"
        Files.readString(elsewhere) shouldBe "$A_RECORD\n"
    }

    /**
     * A fresh repository has a front page before it has a record (#414): what it is, where the guide is, and a count of
     * none. Its first boot writes that page and nothing else.
     */
    @Test
    fun `a fresh repository's first boot commits its front page alone`() {
        startup()

        repo.filesInHead() shouldContainExactly listOf("README.md")
        Files.readString(repo.root.resolve("README.md")) shouldContain "0 problems recorded, 0 passed."
    }

    /**
     * A boot that had nothing to recover does nothing (#292). The front page a first boot writes is the same bytes at
     * every boot after it, so it is no exception.
     */
    @Test
    fun `a boot with nothing left behind commits nothing`() {
        startup()
        val once = repo.subjects()

        startup()

        repo.subjects() shouldContainExactly once
    }

    // The front page becomes the index (#414) ----------------------------------------------------------

    /**
     * A repository as the server left it before #414: the guide seeded as `README.md` and `README.ko.md`, untouched,
     * and recorded in the ledger. The boot moves the guide to `GUIDE.md` and `GUIDE.ko.md`, puts the index on the front
     * page, and commits exactly that.
     */
    @Test
    fun `an untouched guide moves aside for the index, and the commit holds the move alone`() {
        seededAndCommitted()

        boot()

        repo.filesInHead() shouldContainExactly listOf("GUIDE.ko.md", "GUIDE.md", "README.ko.md", "README.md")
        Files.readString(repo.root.resolve("README.md")) shouldContain "# Programmers solving records"
        Files.exists(repo.root.resolve("README.ko.md")) shouldBe false
    }

    /** Whatever a boot changes outside `.ps` it commits, so no new commit is no change. */
    @Test
    fun `a second boot after the move changes nothing`() {
        seededAndCommitted()
        boot()
        val once = repo.subjects()

        boot()

        repo.subjects() shouldContainExactly once
    }

    /** An edited guide is the owner's front page: it stays, and the index stays in `problems/README.md`. */
    @Test
    fun `an edited README stays the front page, and the boot commits the guide alone`() {
        seededAndCommitted()
        repo.write("README.md", "$AN_OLDER_GUIDE\nMy own line.\n")
        repo.git("commit", "--all", "--message", "mine")

        boot()

        Files.readString(repo.root.resolve("README.md")) shouldBe "$AN_OLDER_GUIDE\nMy own line.\n"
        repo.filesInHead() shouldContainExactly listOf("GUIDE.ko.md", "GUIDE.md")
        Files.readString(repo.root.resolve("README.ko.md")) shouldBe AN_OLDER_KOREAN_GUIDE
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

    /** A boot as the server makes one: the seeds while the beans are built, then the startup pass. */
    private fun boot() {
        VaultDashboard(repo.root, ledger()).ensure()
        startup()
    }

    /**
     * The repository as a server before #414 left it: the guide seeded as both READMEs and the dashboard as it ships,
     * each recorded in the ledger, and committed — `.ps` left out, as every commit of the tracker's leaves it.
     */
    private fun seededAndCommitted() {
        val seeds = mapOf(
            "README.md" to AN_OLDER_GUIDE,
            "README.ko.md" to AN_OLDER_KOREAN_GUIDE,
            "dashboard.base" to shipped("dashboard.base"),
        )
        seeds.forEach { (seed, content) ->
            repo.write(seed, content)
            ledger().record(seed, content)
        }
        repo.git("add", "--", *seeds.keys.toTypedArray())
        repo.git("commit", "--message", "seeded")
    }

    private fun ledger() = SeedLedger(repo.root, aStateDirectory(repo.root))

    private fun shipped(seed: String): String =
        checkNotNull(javaClass.getResourceAsStream("/vault/$seed")).bufferedReader().use { it.readText() }

    private fun reconciliation(): StartupReconciliation {
        val git = CommandLineGitSync(repo.root)
        return StartupReconciliation(
            rawSessions(),
            rawLog(),
            BackupReporter(backupLog(), git, clock()),
            attachment(),
            // The pass is exercised in StatementBackfillTest; here it must merely not derail the
            // boot, so its source answers "unreachable" the way the code fetcher above does.
            statementBackfill(),
            git,
            DailyBackup(git, backupLog(), clock(), zone = ZoneId.of("Asia/Seoul")),
        )
    }

    /**
     * A real attachment over a fetcher that can never reach the page, because these tests are
     * about the git half of the boot. It still runs for real — a pass that silently threw would
     * take the commit and the backup down with it, and that is precisely what this class exists
     * to order correctly.
     */
    private fun statementBackfill(): StatementBackfill {
        val store = JsonlRecordStore.under(repo.root)
        return StatementBackfill(
            store = store,
            statements = { _, _ -> null },
            source = { _, _ -> StatementFetch.Unavailable("no page source in this test") },
            artifacts = FileDerivedArtifacts(repo.root, store, Clock.systemDefaultZone()),
            pause = {},
        )
    }

    private fun attachment(): CodeAttachment {
        val store = JsonlRecordStore.under(repo.root)
        return CodeAttachment(
            fetcher = { _, _ -> CodeFetch.Unavailable("no page source in this test") },
            store = store,
            artifacts = FileDerivedArtifacts(repo.root, store, Clock.systemDefaultZone()),
            catalog = anEmptyCatalog(),
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

        /**
         * A guide an older server seeded as a README, recorded in the ledger, and opening as the real one did: under
         * its title, the link to the Korean twin, which is what keeps the twin beside an edited README.
         */
        const val AN_OLDER_GUIDE =
            "# ps-records\n\n**[한국어](README.ko.md)**\n\nThe usage guide an older server seeded.\n"
        const val AN_OLDER_KOREAN_GUIDE = "# ps-records\n\nIts Korean twin, as an older server seeded it.\n"
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
