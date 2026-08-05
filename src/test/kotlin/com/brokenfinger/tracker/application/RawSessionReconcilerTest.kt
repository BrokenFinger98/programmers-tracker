package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aBroadcastFrame
import com.brokenfinger.tracker.support.fixtures.aFrameReader
import com.brokenfinger.tracker.support.fixtures.aQuietGitSync
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Layer test for startup reconciliation (dev rules §6.1).
 *
 * Everything here is real except the problem timer: real files under a `@TempDir`, the
 * production `FileRawSessionLog` writing the raw sessions exactly as a live capture would,
 * and the real `RecordWriter` over a real store. That is deliberate — this code only ever
 * runs after a crash ([[decisions/2026-08-05-capture-pipeline-stages]] accepted costs), so a
 * doubled-out version of it would prove nothing about the files a crash actually leaves.
 * Every stream replayed is a measured capture (dev rules §6.2). Nothing sleeps; the clock is
 * injected.
 */
class RawSessionReconcilerTest {
    @TempDir
    lateinit var root: Path

    private val rawLog by lazy { FileRawSessionLog.under(root) }

    // Reconciled ten minutes after the session that the crash interrupted started.
    private val clock by lazy { Clock.fixed(NOW, ZoneOffset.UTC) }

    // Normal path --------------------------------------------------------------------------

    @Test
    fun `a submit a crash left behind is recorded with the verdict its stored frames carried`() {
        stage(LESSON_ID, broadcastsOf("algorithm-pass.jsonl"))

        val report = reconcile()

        report shouldBe ReconcileReport(recorded = 1)
        val record = records().single()
        record.outcome shouldBe Outcome.JUDGED
        record.verdict shouldBe Verdict.PASS
        record.action shouldBe GradingAction.SUBMIT
    }

    /** The channel is read from the stored envelope, so the language survives the crash too. */
    @Test
    fun `the reconciled record carries the language its stored envelope named`() {
        stage(LESSON_ID, broadcastsOf("algorithm-pass.jsonl"))

        reconcile()

        records().single().language shouldBe "java"
    }

    @Test
    fun `a reconciled submit leaves the work list empty`() {
        stage(LESSON_ID, broadcastsOf("algorithm-pass.jsonl"))

        reconcile()

        rawLog.unprocessed() shouldContainExactly emptyList()
    }

    /**
     * Nothing in a database submit's inner messages says "database" and it never sends a
     * `finish` frame (protocol doc §6) — reconciling it at all proves the problem family came
     * from the stored envelope rather than from a guess.
     */
    @Test
    fun `a database submit is reconciled as one, though only its envelope says so`() {
        stage(SQL_LESSON_ID, broadcastsOf("sql-pass.jsonl"))

        reconcile()

        records().single().verdict shouldBe Verdict.PASS
    }

    /** Time on a problem is measured to the grading, never to whenever the process restarted. */
    @Test
    fun `the elapsed time is measured to when the session started, not to the restart`() {
        stage(LESSON_ID, broadcastsOf("algorithm-pass.jsonl"))

        reconcile()

        records().single().elapsedSec shouldBe ELAPSED_SEC - DOWNTIME_SEC
    }

    // Duplicate safety ---------------------------------------------------------------------

    /**
     * The key is derived from the terminal frame (design §5.2), so a replay of the same stored
     * bytes derives the same key — which is what makes reprocessing safe at all.
     */
    @Test
    fun `the reconciled record is keyed exactly as the live capture keyed it`() {
        val frames = broadcastsOf("algorithm-pass.jsonl")
        stage(LESSON_ID, frames)

        reconcile()

        records().single().captureKey shouldBe CaptureKey.of(LESSON_ID, GradingAction.SUBMIT, frames.last())
    }

    /**
     * A run's frames never move out of `.ps/raw` (design §5.1), so a crash after the record was
     * written leaves the session on the work list forever. Nothing but the writer's capture-key
     * index stands between that and a second record on every restart — including across the
     * restart itself, which is why the second pass gets a writer that rebuilt its index from
     * the log on disk.
     */
    @Test
    fun `reprocessing a session already recorded writes no second record`() {
        stage(LESSON_ID, broadcastsOf("algorithm-run-pass.jsonl"))
        reconcile() shouldBe ReconcileReport(recorded = 1)
        // Not vacuous: the second pass really is handed the same session again.
        rawLog.unprocessed().size shouldBe 1

        val afterRestart = reconcile()

        afterRestart shouldBe ReconcileReport(duplicates = 1)
        records().size shouldBe 1
    }

    // Interrupted sessions -------------------------------------------------------------------

    @Test
    fun `a session whose frames never terminated is filed incomplete, not as a verdict`() {
        stage(LESSON_ID, broadcastsOf("algorithm-pass.jsonl").dropLast(2))

        reconcile()

        val record = records().single()
        record.outcome shouldBe Outcome.INCOMPLETE
        record.verdict shouldBe null
    }

    @Test
    fun `an incomplete session keeps every frame it did receive`() {
        val frames = broadcastsOf("algorithm-pass.jsonl").dropLast(2)
        stage(LESSON_ID, frames)

        reconcile()

        Files.readAllLines(RecordLayout(root).rawAttemptFile(LESSON_ID, "", 1)) shouldContainExactly frames
    }

    // Failure paths --------------------------------------------------------------------------

    /**
     * The expected cause is a crash between the write and its line break, so the damage is at
     * the end of the file — and everything measured before it is still a measurement.
     */
    @Test
    fun `a torn final line costs that line and nothing else`() {
        val frames = broadcastsOf("algorithm-pass.jsonl").dropLast(1)
        stage(LESSON_ID, frames + torn(frames.last()))

        val report = reconcile()

        report shouldBe ReconcileReport(recorded = 1, skippedLines = 1)
        records().single().testcases.size shouldBe 2
    }

    @Test
    fun `a session that cannot be reconciled does not stop the ones behind it`() {
        stage(LESSON_ID, listOf(aBroadcastFrame("""{"type":"hint_used","stage":2}""")), at = EARLIER)
        stage(LESSON_ID, broadcastsOf("algorithm-pass.jsonl"))

        val report = reconcile()

        report shouldBe ReconcileReport(recorded = 1, failed = 1)
        records().single().verdict shouldBe Verdict.PASS
    }

    /** Substituting a default for a failed identifier extraction is what files wrong data. */
    @Test
    fun `a session whose channel cannot be read is left where it is, unrecorded`() {
        stage(LESSON_ID, listOf("""{"identifier":"not an identifier","message":{"action":"submit","type":"start"}}"""))

        val report = reconcile()

        report shouldBe ReconcileReport(failed = 1)
        records() shouldContainExactly emptyList()
        rawLog.unprocessed().size shouldBe 1
    }

    @Test
    fun `a raw directory that was never created is a no-op`() {
        reconcile() shouldBe ReconcileReport()
    }

    @Test
    fun `an empty raw directory is a no-op`() {
        Files.createDirectories(root.resolve(".ps/raw"))

        reconcile() shouldBe ReconcileReport()
    }

    // Harness --------------------------------------------------------------------------------

    /** A fresh writer every pass — a restart is exactly what this code recovers from. */
    private fun reconcile(): ReconcileReport = runBlocking {
        RawSessionReconciler(rawLog, writer(), StaleTimer(ELAPSED_SEC), aFrameReader(), clock).reconcile()
    }

    private fun writer() = RecordWriter.of(
        store = JsonlRecordStore.under(root),
        rawLog = rawLog,
        rawAttemptPath = AttemptRawPath(RecordLayout(root)::rawAttemptFile),
        recordRoot = root,
        git = aQuietGitSync(),
        submissionLog = RecordLayout(root).submissionLog(),
        clock = clock,
        writerDispatcher = Dispatchers.Unconfined,
    )

    /** Writes the frames through the production raw log, exactly as a live capture would. */
    private fun stage(lessonId: Long, frames: List<String>, at: Instant = SESSION_START) {
        val log = FileRawSessionLog(root.resolve(".ps/raw"), Clock.fixed(at, ZoneOffset.UTC))
        val session = log.start(lessonId)
        frames.forEach { log.append(session, it) }
    }

    /** What the live path would have appended: the broadcasts, welcome and ping excluded. */
    private fun broadcastsOf(fixture: String): List<String> = FixtureLoader.broadcastLines(fixture)

    private fun torn(line: String): String = line.take(line.length / 2)

    private fun records(): List<SubmissionRecord> {
        val log = root.resolve("log/submissions.jsonl")
        if (!Files.exists(log)) return emptyList()
        return Files.readAllLines(log).filter { it.isNotBlank() }.map(SubmissionRecordJson::decode)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-05T09:10:00Z")
        val SESSION_START: Instant = Instant.parse("2026-08-05T09:00:00Z")
        val EARLIER: Instant = Instant.parse("2026-08-05T08:00:00Z")
        const val DOWNTIME_SEC = 600L
        const val ELAPSED_SEC = 3600L
        const val LESSON_ID = 120804L
        const val SQL_LESSON_ID = 131528L
    }
}

/**
 * The timer keeps counting while the process is down, so what it reports at reconcile time is
 * always too much — which is the number the reconciler has to correct rather than file.
 */
private class StaleTimer(private val elapsedSec: Long) : ProblemTimer {
    override fun elapsedSecOf(lessonId: Long): Long = elapsedSec

    override fun startIfAbsent(lessonId: Long) = Unit
}
