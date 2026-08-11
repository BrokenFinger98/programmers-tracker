package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aQuietGitSync
import com.brokenfinger.tracker.support.fixtures.aRawSessionId
import com.brokenfinger.tracker.support.fixtures.aSessionOf
import com.brokenfinger.tracker.support.fixtures.aSettledCapture
import com.brokenfinger.tracker.support.fixtures.aTruncatedStream
import com.brokenfinger.tracker.support.fixtures.anAssembledSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
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
 * Layer test for the confined single writer ([[decisions/2026-08-05-write-serialization]]).
 *
 * It drives the real file adapters rather than mocks, because what is being asserted is what
 * ends up on disk: the JSONL line, the attempt number derived from it, and where the original
 * frames came to rest. The clock is injected and no test sleeps.
 */
class RecordWriterTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a submit is written as one JSONL line carrying its verdict`() = runBlocking<Unit> {
        val record = writer().write(aSettledCapture())

        record shouldNotBe null
        logLines().size shouldBe 1
        stored().single().outcome shouldBe Outcome.JUDGED
    }

    @Test
    fun `the record is stamped from the injected clock`() = runBlocking<Unit> {
        val record = writer().write(aSettledCapture())

        record!!.ts.toInstant() shouldBe NOW
    }

    @Test
    fun `the first submit of an unseen problem takes attempt one`() = runBlocking<Unit> {
        writer().write(aSettledCapture())!!.attempt shouldBe 1
    }

    @Test
    fun `each further submit takes the next number`() = runBlocking<Unit> {
        val writer = writer()

        val attempts = (1..3).map { writer.write(aSubmit(it))!!.attempt }

        attempts shouldContainExactly listOf(1, 2, 3)
    }

    @Test
    fun `a run does not consume a number and keeps the previous submit's`() = runBlocking<Unit> {
        val writer = writer()
        writer.write(aSettledCapture())

        writer.write(aRun())!!.attempt shouldBe 1
        writer.write(aSubmit(2))!!.attempt shouldBe 2
    }

    @Test
    fun `a run before the first submit belongs to no attempt`() = runBlocking<Unit> {
        writer().write(aRun())!!.attempt shouldBe AttemptAuthority.NONE
    }

    @Test
    fun `a restarted writer restores the counter from the log rather than a directory scan`() = runBlocking<Unit> {
        writer().write(aSettledCapture())

        writer().write(aSubmit(2))!!.attempt shouldBe 2
    }

    /**
     * Reconciliation re-reading bytes already on disk is the case the index exists for, and it
     * is the only one that asks it now (#161). A live capture no longer does: the socket does
     * not redeliver — a reconnect loses what was broadcast meanwhile rather than repeating it —
     * and the one path that did deliver one grading twice was two channels on a problem, closed
     * at the subscription in #160.
     */
    @Test
    fun `a replay of a grading already written adds no second record`() = runBlocking<Unit> {
        val writer = writer()

        writer.write(aSettledCapture())
        val replay = writer.replay(aSettledCapture())

        replay shouldBe null
        logLines().size shouldBe 1
    }

    @Test
    fun `a dropped replay does not consume an attempt number`() = runBlocking<Unit> {
        val writer = writer()
        writer.write(aSettledCapture())

        writer.replay(aSettledCapture())

        writer.write(aSubmit(2))!!.attempt shouldBe 2
    }

    /**
     * The index is rebuilt from the log, so a replay after a restart is still recognised —
     * which is the whole reason the log is the index (#161 keeps this; it only stops the live
     * path from asking).
     */
    @Test
    fun `dedup survives a restart, because the log is also the dedup index`() = runBlocking<Unit> {
        writer().write(aSettledCapture())

        writer().replay(aSettledCapture()) shouldBe null
        logLines().size shouldBe 1
    }

    @Test
    fun `two different gradings of the same problem are both recorded`() = runBlocking<Unit> {
        val writer = writer()

        writer.write(aSubmit(1))
        writer.write(aSubmit(2))

        logLines().size shouldBe 2
    }

    @Test
    fun `a submit moves its frames to the attempt file once the number exists`() = runBlocking<Unit> {
        val capture = aSettledCapture(rawSessionId = liveRaw("live-1.jsonl"))

        val record = writer().write(capture)!!

        record.rawPath shouldBe "problems/120804-두-수의-곱-구하기/attempts/001.raw.jsonl"
        Files.readString(root.resolve(record.rawPath)) shouldEndWith "\n"
        Files.exists(rawDirectory().resolve("live-1.jsonl")) shouldBe false
        // Discarded rather than set aside: the frames are in the attempt file now, so a copy
        // in `recorded/` would be the duplicate storage this pipeline exists to avoid. Which
        // makes `recorded/` a directory of runs and nothing else — #128 was written on the
        // opposite belief, into the user's own `.gitignore`.
        Files.exists(rawDirectory().resolve("recorded/live-1.jsonl")) shouldBe false
    }

    @Test
    fun `a failed move still leaves the verdict recorded — only the move is recoverable`() = runBlocking<Unit> {
        val capture = aSettledCapture(rawSessionId = aRawSessionId("never-written.jsonl"))

        val record = writer().write(capture)!!

        record.attempt shouldBe 1
        // The copy never happened, so there is no path inside the repository to name (#99).
        record.rawPath.shouldBeNull()
        stored().single().outcome shouldBe Outcome.JUDGED
    }

    /**
     * A run creates no attempt file (design §5.1), so its frames have no home inside the
     * record repository — `rawPath` is null rather than a bare session id resolving to
     * nothing (#99). They are kept all the same, set aside where the reconciler will not
     * re-read them on every boot.
     */
    @Test
    fun `a run has no rawPath, and its frames are kept off the work list`() = runBlocking<Unit> {
        val capture = aRun(rawSessionId = liveRaw("live-run.jsonl"))

        val record = writer().write(capture)!!

        record.rawPath.shouldBeNull()
        Files.exists(rawDirectory().resolve("live-run.jsonl")) shouldBe false
        Files.exists(rawDirectory().resolve("recorded/live-run.jsonl")) shouldBe true
        Files.exists(root.resolve("problems")) shouldBe false
    }

    @Test
    fun `a record is durable before any code is attached`() = runBlocking<Unit> {
        val record = writer().write(aSettledCapture())!!

        record.codePending shouldBe true
        record.codePath shouldBe null
    }

    @Test
    fun `a session that never terminated is still recorded, marked incomplete`() = runBlocking<Unit> {
        val capture = aSettledCapture(session = aSessionOf(aTruncatedStream()), frames = emptyList())

        writer().write(capture)!!.outcome shouldBe Outcome.INCOMPLETE
        logLines().size shouldBe 1
    }

    @Test
    fun `a stream that announced no action is refused instead of filed under a guess`() = runBlocking<Unit> {
        val capture = aSettledCapture(session = aSessionOf(emptyList()))

        shouldThrow<IllegalArgumentException> { writer().write(capture) }
        logLines() shouldBe emptyList()
    }

    @Test
    fun `a failed append releases the capture key so a retry can still record it`() = runBlocking<Unit> {
        val store = FailingStore(JsonlRecordStore.under(root))
        val writer = writer(store)

        shouldThrow<IllegalStateException> { writer.write(aSettledCapture()) }
        store.failing = false

        writer.write(aSettledCapture()) shouldNotBe null
    }

    private fun writer(store: RecordStore = JsonlRecordStore.under(root)): RecordWriter {
        val layout = RecordLayout(root)
        return RecordWriter.of(
            store = store,
            rawLog = FileRawSessionLog(rawDirectory()),
            rawAttemptPath = AttemptRawPath(layout::rawAttemptFile),
            recordRoot = root,
            git = aQuietGitSync(),
            submissionLog = layout.submissionLog(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            writerDispatcher = Dispatchers.Unconfined,
        )
    }

    // Each grading of one problem ends on its own terminal frame, so each has its own key.
    // Live is not replay ------------------------------------------------------------------------

    /**
     * Two submissions with byte-identical frames are two gradings when they arrive live (#161).
     *
     * SQL is where this bites. Its frames carry no `run_time` and no `memory_size`, so the same
     * query submitted twice produces the same bytes down to the last character — measured
     * 2026-08-11 on lesson 151136, where the second submit was dropped as a replay. Java only
     * escapes because its timings jitter, which is luck rather than design.
     *
     * The capture key exists so a **replay of stored bytes** does not write a second record.
     * A live capture is not a replay: it is a thing that just happened.
     */
    @Test
    fun `two live gradings with identical frames are both recorded`() = runBlocking<Unit> {
        val writer = writer()
        val frames = listOf("""{"type":"finish","sql":"identical"}""")

        writer.write(aSettledCapture(rawSessionId = liveRaw("one.jsonl"), frames = frames)) shouldNotBe null
        writer.write(aSettledCapture(rawSessionId = liveRaw("two.jsonl"), frames = frames)) shouldNotBe null

        stored() shouldHaveSize 2
    }

    /** And the property the key exists for is untouched: a replay of the same bytes is dropped. */
    @Test
    fun `a replay of a capture already written is still dropped`() = runBlocking<Unit> {
        val writer = writer()
        val frames = listOf("""{"type":"finish","sql":"identical"}""")

        writer.write(aSettledCapture(rawSessionId = liveRaw("one.jsonl"), frames = frames)) shouldNotBe null
        writer.replay(aSettledCapture(rawSessionId = liveRaw("one.jsonl"), frames = frames)) shouldBe null

        stored() shouldHaveSize 1
    }

    private fun aSubmit(nth: Int) = aSettledCapture(frames = listOf("""{"type":"finish","nth":$nth}"""))

    private fun aRun(rawSessionId: RawSessionId = aRawSessionId()) = aSettledCapture(
        session = anAssembledSession("algorithm-run-pass.jsonl"),
        rawSessionId = rawSessionId,
    )

    private fun liveRaw(name: String): RawSessionId {
        val id = aRawSessionId(name)
        FileRawSessionLog(rawDirectory()).append(id, """{"type":"finish"}""")
        return id
    }

    private fun rawDirectory(): Path = root.resolve(".ps/raw")

    private fun logLines(): List<String> {
        val log = root.resolve("log/submissions.jsonl")
        if (!Files.exists(log)) return emptyList()
        return Files.readAllLines(log).filter { it.isNotBlank() }
    }

    private fun stored(): List<SubmissionRecord> = logLines().map(SubmissionRecordJson::decode)

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-04T05:23:01Z")
    }
}

/** Fails every append until told otherwise — the shape a full disk leaves behind. */
private class FailingStore(private val delegate: RecordStore) : RecordStore {
    var failing = true

    override fun append(line: String) {
        check(!failing) { "append refused" }
        delegate.append(line)
    }

    override fun read(): List<RecordedSubmission> = delegate.read()
}
