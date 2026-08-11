package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileExampleStore
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SensorObservation
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aBroadcastFrame
import com.brokenfinger.tracker.support.fixtures.aCatalogEntry
import com.brokenfinger.tracker.support.fixtures.aCatalogOf
import com.brokenfinger.tracker.support.fixtures.aQuietGitSync
import com.brokenfinger.tracker.support.fixtures.aSqlChannel
import com.brokenfinger.tracker.support.fixtures.acceptedFrames
import com.brokenfinger.tracker.support.fixtures.anAlgorithmChannel
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import com.brokenfinger.tracker.support.fixtures.anObservedFrame
import com.brokenfinger.tracker.support.fixtures.observedFrames
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        consume(capture, observedFrames("algorithm-pass.jsonl").take(1))

        journal shouldContainExactly emptyList()
    }

    @Test
    fun `a frame nothing could parse is appended all the same`() {
        val capture = capture()
        consume(capture, observedFrames("algorithm-pass.jsonl").take(2))

        consume(capture, listOf(anObservedFrame("{ not json")))

        rawLog.frames().last() shouldBe "{ not json"
    }

    @Test
    fun `an unknown message type is preserved without ending the grading`() {
        val stream = observedFrames("algorithm-pass.jsonl")
        val unknown = anObservedFrame(aBroadcastFrame("""{"type":"hint_used","stage":2}"""))

        consume(capture(), stream.dropLast(1) + unknown + stream.last())

        rawLog.frames().size shouldBe 7
        records().single().verdict shouldBe Verdict.PASS
    }

    // Grading lifecycle and pinning --------------------------------------------------------

    @Test
    fun `a starting grading pins the subscription against eviction`() {
        consume(capture(), observedFrames("algorithm-pass.jsonl").take(2))

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
    fun `the record takes its elapsed time from the timer, not from the frames`() {
        consume(capture(), "algorithm-pass.jsonl")

        records().single().elapsedSec shouldBe ELAPSED_SEC
    }

    @Test
    fun `the whole grading keys the capture, not only the frame that ended it`() {
        consume(capture(), "algorithm-pass.jsonl")

        val expected = CaptureKey.of(LESSON_ID, GradingAction.SUBMIT, acceptedFrames("algorithm-pass.jsonl"))
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

        consume(capture(sql), observedFrames("sql-pass.jsonl", sql))

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

    // Stage 3 hand-off -----------------------------------------------------------------------

    @Test
    fun `a settled grading is handed to stage 3, which is what makes its files appear`() {
        val attached = mutableListOf<SubmissionRecord>()

        consume(capture(attachment = recording(attached)), "algorithm-pass.jsonl")

        attached.single().verdict shouldBe Verdict.PASS
    }

    /**
     * Two live gradings with identical frames are two gradings (#161). This used to assert the
     * second was dropped, and that reading is what silently discarded a second SQL submission
     * of the same query — SQL frames carry no timing, so identical bytes are the normal case
     * rather than a sign of duplication.
     *
     * A live capture is a thing that just happened. The index that recognises a *replay* is
     * consulted on the reconciliation path and nowhere else.
     */
    @Test
    fun `two live gradings with identical frames both reach stage 3`() {
        val attached = mutableListOf<SubmissionRecord>()
        val capture = capture(attachment = recording(attached))

        consume(capture, "algorithm-pass.jsonl")
        consume(capture, "algorithm-pass.jsonl")

        attached shouldHaveSize 2
    }

    /** The verdict is already durable; a fetch that blew up may not take the record with it. */
    @Test
    fun `an attachment that throws costs files, not the record and not the stream`() {
        val capture = capture(attachment = { error("the page fetch blew up") })

        consume(capture, "algorithm-pass.jsonl")
        consume(capture, "algorithm-wrong.jsonl")

        records().map { it.verdict } shouldContainExactly listOf(Verdict.PASS, Verdict.WRONG)
    }

    // Failure paths ------------------------------------------------------------------------

    /**
     * A terminal frame with no grading open means its `start` was missed — a reconnect
     * landed mid-grading, or the sensor announced the problem after Submit. No record can
     * come of it, because the `start` is what carries the action and the identity. But
     * dropping it silently is how a change in Programmers' framing stays invisible
     * (dev rules §2.3), so it is kept where a person can read it (#107).
     */
    @Test
    fun `a grading frame with no grading in flight is kept, not dropped`() {
        val capture = capture()

        consume(capture, observedFrames("algorithm-pass.jsonl").takeLast(1))

        journal shouldContainExactly listOf(ORPHANED)
        rawLog.orphanedFrames() shouldHaveSize 1
        records() shouldContainExactly emptyList()
    }

    /** Protocol noise is not evidence: a frame carrying no grading facts is still just dropped. */
    @Test
    fun `a frame carrying no grading facts is not kept`() {
        consume(capture(), listOf(ObservedFrame("""{"type":"confirm_subscription","identifier":"{}"}""")))

        journal shouldContainExactly emptyList()
        rawLog.orphanedFrames() shouldContainExactly emptyList()
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
     * Both error frames of `algorithm-run-error.jsonl` belong to the **same** run: the run
     * path reports one per diagnostic and ends on `result` (protocol doc §7). This test used
     * to assert the opposite — that the second was an orphan "belonging to no grading" — and
     * that reading was the defect, not the behaviour (#152).
     *
     * The capture therefore does not settle here: the fixture holds no `result`, so the
     * grading stays open until the silence deadline abandons it. Nothing recorded is the
     * honest answer to a stream that never ended.
     */
    @Test
    fun `both diagnostics of a failing run belong to one grading`() {
        consume(capture(), "algorithm-run-error.jsonl")

        rawLog.frames().size shouldBe 3
        records().shouldBeEmpty()
    }

    @Test
    fun `a grading that a new start cut short is still recorded, marked incomplete`() {
        val capture = capture()

        consume(capture, observedFrames("algorithm-pass.jsonl").dropLast(2))
        consume(capture, "algorithm-wrong.jsonl")

        records().map { it.outcome } shouldContainExactly listOf(Outcome.INCOMPLETE, Outcome.JUDGED)
    }

    // Catalog ------------------------------------------------------------------------------
    //
    // The record carries what the shipped catalog knows about the problem
    // ([[decisions/2026-08-06-shipped-problem-catalog]]). Before it existed every record was
    // written with an empty title, so a problem directory was a bare lesson number and design
    // §5.1's `<lessonId>-<slug>` was not what actually happened (#59).

    @Test
    fun `a recorded grading carries the catalogued title`() {
        consume(capture(), "algorithm-pass.jsonl")

        records().single().title shouldBe "두 수의 곱 구하기"
    }

    @Test
    fun `it carries the rest of what the catalog knows, so weakness analysis has an axis`() {
        consume(capture(), "algorithm-pass.jsonl")

        val record = records().single()

        record.level shouldBe 0
        record.part shouldBe "코딩테스트 입문"
        record.acceptanceRate shouldBe 91
        record.tags shouldContainExactly listOf("implementation", "arithmetic")
    }

    /**
     * The shipped catalog is a snapshot, so a problem published after it was built is simply
     * unknown. That must record the grading anyway — the grading cannot be fetched again and
     * the title can.
     */
    @Test
    fun `a problem the catalog has never heard of is still recorded`() {
        consume(capture(catalog = anEmptyCatalog()), "algorithm-pass.jsonl")

        records().shouldHaveSize(1)
    }

    /**
     * And it stays empty. A stand-in like "Unknown problem" would be indistinguishable from a
     * title we actually know ([[concepts/assumption-vs-measurement]]), and `RecordLayout`
     * already falls back to the bare lesson id on its own.
     */
    @Test
    fun `an unknown problem leaves the catalogued fields absent rather than filled in`() {
        consume(capture(catalog = anEmptyCatalog()), "algorithm-pass.jsonl")

        val record = records().single()

        record.title shouldBe ""
        record.level.shouldBeNull()
        record.tags.shouldBeEmpty()
    }

    // Examples ----------------------------------------------------------------------------

    /**
     * The run examples reach the problem directory (#37 step 1): the `start` frame ships
     * them inline (protocol §7), and the runner is generated from this file. Driven by the
     * measured run capture, whose example values are our substitutions of the site's (#62).
     */
    @Test
    fun `a run's examples land beside the problem as examples json`() {
        consume(capture(), "algorithm-run-pass.jsonl")

        val file = root.resolve("problems/120804-두-수의-곱-구하기/examples.json")
        Files.readString(file) shouldContain "6, 7"
    }

    /** A submit announces no examples and must not blank what the preceding run wrote. */
    @Test
    fun `a submit after a run leaves the examples file alone`() {
        val capture = capture()
        consume(capture, "algorithm-run-pass.jsonl")

        consume(capture, "algorithm-pass.jsonl")

        val file = root.resolve("problems/120804-두-수의-곱-구하기/examples.json")
        Files.readString(file) shouldContain "6, 7"
    }

    // Harness ------------------------------------------------------------------------------

    private fun recording(into: MutableList<SubmissionRecord>) = RecordAttachment {
        into += it
        AttachOutcome.ATTACHED
    }

    private fun capture(
        channel: ChannelKey = anAlgorithmChannel(),
        store: RecordStore = JsonlRecordStore.under(root),
        attachment: RecordAttachment = RecordAttachment { AttachOutcome.DEFERRED },
        catalog: ProblemCatalog = aCatalogOf(aCatalogEntry()),
    ): ChannelCapture {
        registry.watch(channel, NOW)
        return ChannelCapture(channel, rawLog, registry, writerOf(store), FixedTimer(ELAPSED_SEC), attachment, catalog)
    }

    private fun writerOf(store: RecordStore) = RecordWriter.of(
        store = RecordingStore(store, journal),
        rawLog = rawLog,
        rawAttemptPath = AttemptRawPath(RecordLayout(root)::rawAttemptFile),
        recordRoot = root,
        git = aQuietGitSync(),
        submissionLog = RecordLayout(root).submissionLog(),
        examples = FileExampleStore(RecordLayout(root)),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        writerDispatcher = Dispatchers.Unconfined,
    )

    private fun consume(capture: ChannelCapture, fixture: String) = consume(capture, observedFrames(fixture))

    private fun consume(capture: ChannelCapture, frames: List<ObservedFrame>) = runBlocking {
        frames.forEach { capture.onFrame(it) }
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
        const val ORPHANED = "orphaned"
    }
}

/** Time is somebody else's port here — the capture must read it, never compute it. */
private class FixedTimer(private val elapsedSec: Long) : ProblemTimer {
    override fun elapsedSecOf(lessonId: Long): Long = elapsedSec

    override fun startIfAbsent(lessonId: Long) = Unit

    override fun observed(lessonId: Long, observation: SensorObservation) {
        observations[lessonId] = observation
    }

    override fun observationOf(lessonId: Long): SensorObservation? = observations[lessonId]

    val observations = mutableMapOf<Long, SensorObservation>()
}

/** In-memory raw log that records the order of its appends against the record writes. */
private class RecordingRawSessionLog(private val journal: MutableList<String>) : RawSessionLog {
    private val sessions = linkedMapOf<RawSessionId, MutableList<String>>()
    private val orphans = mutableListOf<String>()
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

    // Retiring the source is bookkeeping the capture tests do not observe; the frames stay
    // readable here so `frames()` still answers what was appended.
    override fun discard(session: RawSessionId) = Unit

    override fun setAside(session: RawSessionId) = Unit

    override fun orphaned(lessonId: Long, frameText: String) {
        journal += "orphaned"
        orphans += frameText
    }

    override fun unprocessed(): List<RawSession> = emptyList()

    /** Every frame appended to the most recent session. */
    fun frames(): List<String> = sessions.values.last().toList()

    /** Frames kept because they belonged to no grading (#107). */
    fun orphanedFrames(): List<String> = orphans.toList()
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
