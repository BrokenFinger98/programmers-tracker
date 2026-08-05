package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aRawSessionId
import com.brokenfinger.tracker.support.fixtures.aSettledCapture
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proof of decision 1 of [[decisions/2026-08-05-write-serialization]] — derived writes are
 * serialized by dispatcher confinement, and the caller's coroutine is not the thing doing
 * the serializing.
 *
 * The store is instrumented rather than mocked: it counts how many writes are ever inside it
 * at once and keeps a deliberately race-prone mirror of the log, so an unserialized writer
 * fails this test by losing lines rather than by an assertion about implementation details.
 * No test sleeps; concurrency comes from real coroutines on a real multi-threaded dispatcher.
 */
class RecordWriterSerializationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `concurrent settlements never enter the write section together`() = runBlocking<Unit> {
        val store = observedStore()

        writeConcurrently(store, CAPTURES)

        store.peak.get() shouldBe 1
    }

    @Test
    fun `every line of the log survives concurrent settlements whole`() = runBlocking<Unit> {
        val store = observedStore()

        writeConcurrently(store, CAPTURES)

        logLines().size shouldBe CAPTURES
        logLines().forEach { SubmissionRecordJson.decode(it) }
        store.mirror.lineSequence().filter { it.isNotBlank() }.count() shouldBe CAPTURES
    }

    @Test
    fun `every concurrent submit takes a number of its own`() = runBlocking<Unit> {
        val store = observedStore()

        writeConcurrently(store, CAPTURES)

        attempts() shouldContainExactly (1..CAPTURES).toList()
    }

    @Test
    fun `each attempt's frames land in a file of its own`() = runBlocking<Unit> {
        val store = observedStore()

        writeConcurrently(store, CAPTURES)

        val attemptFiles = Files.list(attemptsDirectory()).use { it.toList() }
        attemptFiles.size shouldBe CAPTURES
    }

    @Test
    fun `the write runs off the caller's thread, so a socket read loop is never blocked`() {
        val store = observedStore()
        val caller = Executors.newSingleThreadExecutor()

        val callerThread = runBlocking(caller.asCoroutineDispatcher()) {
            writer(store).write(aSettledCapture())
            Thread.currentThread().name
        }

        caller.shutdownNow()
        store.threads.single() shouldNotBe callerThread
    }

    private suspend fun writeConcurrently(store: RecordStore, count: Int) {
        val writer = writer(store)
        val captures = (1..count).map { capture(it) }
        coroutineScope {
            // Dispatchers.Default is genuinely parallel, so the writer is the only thing
            // that can be keeping these apart.
            captures.forEach { capture -> launch(Dispatchers.Default) { writer.write(capture) } }
        }
    }

    // Each capture is a distinct grading — its own terminal frame and its own raw log,
    // written up front so the concurrent phase measures only the writer.
    private fun capture(nth: Int): SettledCapture {
        val id = aRawSessionId("live-$nth.jsonl")
        FileRawSessionLog(rawDirectory()).append(id, """{"type":"finish","nth":$nth}""")
        return aSettledCapture(rawSessionId = id, terminalFrame = """{"type":"finish","nth":$nth}""")
    }

    private fun writer(store: RecordStore): RecordWriter {
        val layout = RecordLayout(root)
        return RecordWriter.of(
            store = store,
            rawLog = FileRawSessionLog(rawDirectory()),
            rawAttemptPath = AttemptRawPath(layout::rawAttemptFile),
            recordRoot = root,
            clock = Clock.fixed(Instant.parse("2026-08-04T05:23:01Z"), ZoneOffset.UTC),
        )
    }

    private fun observedStore() = ObservedStore(JsonlRecordStore.under(root))

    private fun attempts(): List<Int> = logLines().map { SubmissionRecordJson.decode(it).attempt }.sorted()

    private fun attemptsDirectory(): Path = root.resolve("problems/120804-두-수의-곱-구하기/attempts")

    private fun rawDirectory(): Path = root.resolve(".ps/raw")

    private fun logLines(): List<String> =
        Files.readAllLines(root.resolve("log/submissions.jsonl")).filter { it.isNotBlank() }

    private companion object {
        /** Far past the 8 concurrent subscriptions the design allows (design §4.1). */
        const val CAPTURES = 64
    }
}

/**
 * A store that reports on its own concurrency. [mirror] is appended with a plain
 * read-modify-write around a yield: under two concurrent writers it loses lines, which is
 * exactly the corruption the confinement has to prevent.
 */
private class ObservedStore(private val delegate: RecordStore) : RecordStore {
    val peak = AtomicInteger()
    val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()
    var mirror: String = ""
    private val inFlight = AtomicInteger()

    override fun append(line: String) {
        peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        threads += Thread.currentThread().name
        val seen = mirror
        Thread.yield()
        mirror = seen + line + "\n"
        delegate.append(line)
        inFlight.decrementAndGet()
    }

    override fun read(): List<RecordedSubmission> = delegate.read()
}
