package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.support.fixtures.aBroadcastFrame
import com.brokenfinger.tracker.support.fixtures.aCableEvent
import com.brokenfinger.tracker.support.fixtures.aSqlChannel
import com.brokenfinger.tracker.support.fixtures.aTerminalFrame
import com.brokenfinger.tracker.support.fixtures.anAlgorithmChannel
import com.brokenfinger.tracker.support.fixtures.cableEvents
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
 * Layer test for the per-subscription frame pipeline (dev rules §6.1).
 *
 * Every stream is a measured capture replayed through the production frame routing
 * (dev rules §6.2), so what is asserted is what the socket loop actually hands over. The raw
 * log is a recording double rather than a file, because the order of *stage 1 before
 * everything else* ([[decisions/2026-08-05-capture-pipeline-stages]]) is the property under
 * test; the record writer is the real one, over a temp directory, with its clock injected.
 * Nothing here sleeps.
 */
class ChannelCaptureTest {
    @TempDir
    lateinit var root: Path

    private val journal = mutableListOf<String>()
    private val rawLog = RecordingRawSessionLog(journal)
    private val registry = SubscriptionRegistry()

    // Stage 1 ------------------------------------------------------------------------------

    @Test
    fun `every frame of a grading is appended before the record it produces is written`() {
        consume(capture(), "algorithm-pass.jsonl")

        journal shouldContainExactly List(6) { APPEND } + WRITE
    }

    @Test
    fun `the frames that precede a grading belong to none, so nothing is appended`() {
        val capture = capture()

        consume(capture, cableEvents("algorithm-pass.jsonl").take(1))

        journal shouldContainExactly emptyList()
    }

    @Test
    fun `a frame nothing could parse is appended all the same`() {
        val capture = capture()
        consume(capture, cableEvents("algorithm-pass.jsonl").take(2))

        consume(capture, listOf(aCableEvent("{ not json")))

        rawLog.frames().last() shouldBe "{ not json"
    }

    @Test
    fun `an unknown message type is preserved without ending the grading`() {
        val stream = cableEvents("algorithm-pass.jsonl")
        val unknown = aCableEvent(aBroadcastFrame("""{"type":"hint_used","stage":2}"""))

        consume(capture(), stream.dropLast(1) + unknown + stream.last())

        rawLog.frames().size shouldBe 7
        records().single().verdict shouldBe Verdict.PASS
    }

    // Grading lifecycle and pinning --------------------------------------------------------

    @Test
    fun `a starting grading pins the subscription against eviction`() {
        consume(capture(), cableEvents("algorithm-pass.jsonl").take(2))

        registry.snapshot().single().pinned shouldBe true
    }

    @Test
    fun `a settled grading releases the pin`() {
        consume(capture(), "algorithm-pass.jsonl")

        registry.snapshot().single().pinned shouldBe false
    }

    @Test
    fun `a passing submit is recorded with the verdict its frames carried`() {
        consume(capture(), "algorithm-pass.jsonl")

        val record = records().single()
        record.outcome shouldBe Outcome.JUDGED
        record.verdict shouldBe Verdict.PASS
        record.action shouldBe GradingAction.SUBMIT
    }

    /** Nothing may look measured that was not: the catalog title arrives on its own schedule. */
    @Test
    fun `the record carries no title and takes its elapsed time from the timer`() {
        consume(capture(), "algorithm-pass.jsonl")

        val record = records().single()
        record.title shouldBe ""
        record.elapsedSec shouldBe ELAPSED_SEC
    }

    @Test
    fun `the exact terminal frame keys the capture`() {
        consume(capture(), "algorithm-pass.jsonl")

        val expected = CaptureKey.of(LESSON_ID, GradingAction.SUBMIT, aTerminalFrame("algorithm-pass.jsonl"))
        records().single().captureKey shouldBe expected
    }

    /** One subscription outlives many gradings — the second must not join the first. */
    @Test
    fun `two gradings in sequence are recorded as two attempts`() {
        val capture = capture()

        consume(capture, "algorithm-pass.jsonl")
        consume(capture, "algorithm-wrong.jsonl")

        records().map { it.attempt } shouldContainExactly listOf(1, 2)
        records().map { it.verdict } shouldContainExactly listOf(Verdict.PASS, Verdict.WRONG)
    }

    // A database submit never sends finish; waiting for one hangs forever (protocol doc §6).
    @Test
    fun `a database submit settles at result_lesson_challenge`() {
        val sql = aSqlChannel()

        consume(capture(sql), cableEvents("sql-pass.jsonl", sql))

        records().single().verdict shouldBe Verdict.PASS
    }

    // Bound error text ---------------------------------------------------------------------

    @Test
    fun `the error text of the preceding run promotes an indistinguishable submit`() {
        val capture = capture()

        consume(capture, "algorithm-run-error.jsonl")
        consume(capture, "algorithm-compile.jsonl")

        records().last().verdict shouldBe Verdict.COMPILE_ERROR
    }

    /** Consumed once. Ten edits later the same compiler output must promote nothing. */
    @Test
    fun `the binding does not survive the submit that used it`() {
        val capture = capture()
        consume(capture, "algorithm-run-error.jsonl")
        consume(capture, "algorithm-compile.jsonl")

        consume(capture, "algorithm-runtime.jsonl")

        records().last().verdict shouldBe Verdict.RUNTIME_ERROR
    }

    @Test
    fun `a later run supersedes the binding of the earlier one`() {
        val capture = capture()
        consume(capture, "algorithm-run-error.jsonl")

        consume(capture, "algorithm-run-pass.jsonl")
        consume(capture, "algorithm-runtime.jsonl")

        records().last().verdict shouldBe Verdict.RUNTIME_ERROR
    }

    // Failure paths ------------------------------------------------------------------------

    @Test
    fun `a terminal frame with no grading in flight is ignored`() {
        consume(capture(), cableEvents("algorithm-pass.jsonl").takeLast(1))

        journal shouldContainExactly emptyList()
        records() shouldContainExactly emptyList()
    }

    /** More gradings will follow, so a write that failed must not end the subscription. */
    @Test
    fun `a failing writer costs one record, not the stream`() {
        val store = RefusingStore(JsonlRecordStore.under(root))
        val capture = capture(store = store)

        consume(capture, "algorithm-pass.jsonl")
        store.failing = false
        consume(capture, "algorithm-wrong.jsonl")

        records().single().verdict shouldBe Verdict.WRONG
    }

    /**
     * The trailing error of `algorithm-run-error.jsonl` arrives after the first one already
     * terminated the stream — a measured frame belonging to no grading (protocol doc §7).
     */
    @Test
    fun `a frame trailing a settled grading is dropped rather than crashing the stream`() {
        consume(capture(), "algorithm-run-error.jsonl")

        rawLog.frames().size shouldBe 2
        records().single().outcome shouldBe Outcome.UNKNOWN
    }

    @Test
    fun `a grading that a new start cut short is still recorded, marked incomplete`() {
        val capture = capture()

        consume(capture, cableEvents("algorithm-pass.jsonl").dropLast(2))
        consume(capture, "algorithm-wrong.jsonl")

        records().map { it.outcome } shouldContainExactly listOf(Outcome.INCOMPLETE, Outcome.JUDGED)
    }

    // Harness ------------------------------------------------------------------------------

    private fun capture(
        channel: ChannelKey = anAlgorithmChannel(),
        store: RecordStore = JsonlRecordStore.under(root),
    ): ChannelCapture {
        registry.watch(channel, NOW)
        return ChannelCapture(channel, rawLog, registry, writerOf(store), FixedTimer(ELAPSED_SEC))
    }

    private fun writerOf(store: RecordStore) = RecordWriter.of(
        store = RecordingStore(store, journal),
        rawLog = rawLog,
        rawAttemptPath = AttemptRawPath(RecordLayout(root)::rawAttemptFile),
        recordRoot = root,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        writerDispatcher = Dispatchers.Unconfined,
    )

    private fun consume(capture: ChannelCapture, fixture: String) = consume(capture, cableEvents(fixture))

    private fun consume(capture: ChannelCapture, events: List<CableEvent>) = runBlocking {
        events.forEach { capture.onEvent(it) }
    }

    private fun records(): List<SubmissionRecord> {
        val log = root.resolve("log/submissions.jsonl")
        if (!Files.exists(log)) return emptyList()
        return Files.readAllLines(log).filter { it.isNotBlank() }.map(SubmissionRecordJson::decode)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-05T09:00:00Z")
        const val LESSON_ID = 120804L
        const val ELAPSED_SEC = 847L
        const val APPEND = "append"
        const val WRITE = "write"
    }
}

/** Time is somebody else's port here — the capture must read it, never compute it. */
private class FixedTimer(private val elapsedSec: Long) : ProblemTimer {
    override fun elapsedSecOf(lessonId: Long): Long = elapsedSec

    override fun startIfAbsent(lessonId: Long) = Unit
}

/** In-memory raw log that records the order of its appends against the record writes. */
private class RecordingRawSessionLog(private val journal: MutableList<String>) : RawSessionLog {
    private val sessions = linkedMapOf<RawSessionId, MutableList<String>>()
    private var opened = 0

    override fun start(lessonId: Long): RawSessionId {
        opened++
        return RawSessionId("live-$opened-$lessonId.jsonl").also { sessions[it] = mutableListOf() }
    }

    override fun append(session: RawSessionId, frameText: String) {
        journal += "append"
        checkNotNull(sessions[session]) { "no such raw session" } += frameText
    }

    override fun complete(session: RawSessionId, destination: Path): Path {
        checkNotNull(sessions[session]) { "no such raw session" }
        return destination
    }

    override fun unprocessed(): List<RawSession> = emptyList()

    /** Every frame appended to the most recent session. */
    fun frames(): List<String> = sessions.values.last().toList()
}

/** Records that a write happened, so append-then-write ordering is observable. */
private class RecordingStore(private val delegate: RecordStore, private val journal: MutableList<String>) :
    RecordStore {
    override fun append(line: String) {
        journal += "write"
        delegate.append(line)
    }

    override fun read(): List<RecordedSubmission> = delegate.read()
}

/** Fails every append until told otherwise — the shape a full disk leaves behind. */
private class RefusingStore(private val delegate: RecordStore) : RecordStore {
    var failing = true

    override fun append(line: String) {
        check(!failing) { "append refused" }
        delegate.append(line)
    }

    override fun read(): List<RecordedSubmission> = delegate.read()
}
