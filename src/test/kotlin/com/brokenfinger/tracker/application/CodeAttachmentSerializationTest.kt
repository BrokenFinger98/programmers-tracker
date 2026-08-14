package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.support.fixtures.aQuietGitSync
import com.brokenfinger.tracker.support.fixtures.aRawSessionId
import com.brokenfinger.tracker.support.fixtures.aSettledCapture
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Proof that stage 3 shares stage 2's confinement — the companion to
 * [RecordWriterSerializationTest], in the same style and for the same decision
 * ([[decisions/2026-08-05-write-serialization]] decision 1).
 *
 * The attachment writes to the *same* three places a record write does: the submission log,
 * the problem directory and, through the diff, the log again as a reader. If it ran on a
 * dispatcher of its own, a correction line could interleave with an allocation, and the diff
 * could read the log between another grading's append and its attempt file. So both stages
 * are given one dispatcher and one probe counts how many writes are ever inside it at once.
 *
 * The second test pins the other half of the rule: the *fetch* must stay outside. A page
 * round trip held inside the single writer thread would stall every other grading behind the
 * network, which is exactly what a late, retryable attachment exists not to do.
 *
 * No test sleeps; concurrency comes from real coroutines on a real multi-threaded dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeAttachmentSerializationTest {
    @TempDir
    lateinit var root: Path

    private val probe = ConfinementProbe()
    private val writerDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Test
    fun `record writes and code attachments never enter the write section together`() = runBlocking<Unit> {
        val writer = writer()
        val attachment = attachment { CODE }

        coroutineScope {
            // Dispatchers.Default is genuinely parallel, so the shared dispatcher is the only
            // thing that can be keeping these apart.
            (1..CAPTURES).forEach { nth -> launch(Dispatchers.Default) { attach(writer, attachment, nth) } }
        }

        probe.peak.get() shouldBe 1
    }

    @Test
    fun `every attachment leaves a correction of its own, and the log survives them whole`() = runBlocking<Unit> {
        val writer = writer()
        val attachment = attachment { CODE }

        coroutineScope {
            (1..CAPTURES).forEach { nth -> launch(Dispatchers.Default) { attach(writer, attachment, nth) } }
        }

        logLines() shouldHaveSize CAPTURES * 2
        RecordHistory.of(store().read()).filter { it.isCodeAttached() } shouldHaveSize CAPTURES
    }

    @Test
    fun `the page fetch is not held inside the confined writer`() {
        val caller = Executors.newSingleThreadExecutor()
        val fetchThreads = ConcurrentHashMap.newKeySet<String>()
        val attachment = attachment { fetchThreads += Thread.currentThread().name }

        val callerThread = runBlocking(caller.asCoroutineDispatcher()) {
            attachment.attach(recorded(writer(), 1))
            Thread.currentThread().name
        }

        caller.shutdownNow()
        fetchThreads.single() shouldBe callerThread
        probe.threads.forEach { it shouldNotBe callerThread }
    }

    private suspend fun attach(writer: RecordWriter, attachment: CodeAttachment, nth: Int) {
        attachment.attach(recorded(writer, nth))
    }

    private suspend fun recorded(writer: RecordWriter, nth: Int): SubmissionRecord {
        val id = aRawSessionId("live-$nth.jsonl")
        val frame = """{"type":"finish","nth":$nth}"""
        FileRawSessionLog(rawDirectory()).append(id, frame)
        return writer.write(aSettledCapture(rawSessionId = id, frames = listOf(frame)))!!
    }

    private fun attachment(onFetch: () -> Unit) = CodeAttachment(
        fetcher = { _, _ -> CodeFetch.Fetched(CODE).also { onFetch() } },
        store = ProbedStore(store(), probe),
        artifacts = ProbedArtifacts(FileDerivedArtifacts(root, ProbedStore(store(), probe)), probe),
        catalog = anEmptyCatalog(),
        writerDispatcher = writerDispatcher,
    )

    private fun writer() = RecordWriter.of(
        store = ProbedStore(store(), probe),
        rawLog = FileRawSessionLog(rawDirectory()),
        rawAttemptPath = AttemptRawPath(RecordLayout(root)::rawAttemptFile),
        recordRoot = root,
        // Committing is off: this test is about how writes interleave on the writer, and a real
        // git would add its own ordering to the thing being measured.
        git = aQuietGitSync(),
        submissionLog = RecordLayout(root).submissionLog(),
        clock = Clock.fixed(Instant.parse("2026-08-04T05:23:01Z"), ZoneOffset.UTC),
        writerDispatcher = writerDispatcher,
    )

    private fun store(): RecordStore = JsonlRecordStore.under(root)

    private fun rawDirectory(): Path = root.resolve(".ps/raw")

    private fun logLines(): List<String> =
        Files.readAllLines(root.resolve("log/submissions.jsonl")).filter { it.isNotBlank() }

    private companion object {
        /** Enough to expose an interleaving without making every attachment diff a large file. */
        const val CAPTURES = 16
        const val CODE = "class Solution {}"
    }
}

/** Counts how many derived writes are ever inside the confined section at once, and on what. */
private class ConfinementProbe {
    val peak = AtomicInteger()
    val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inFlight = AtomicInteger()

    fun <T> around(write: () -> T): T {
        peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        threads += Thread.currentThread().name
        // Yielding widens the window an unserialized second writer would slip through.
        Thread.yield()
        return try {
            write()
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

private class ProbedStore(private val delegate: RecordStore, private val probe: ConfinementProbe) : RecordStore {
    override fun append(line: String) = probe.around { delegate.append(line) }

    override fun read(): List<RecordedSubmission> = delegate.read()
}

private class ProbedArtifacts(private val delegate: DerivedArtifacts, private val probe: ConfinementProbe) :
    DerivedArtifacts {
    override fun writeCode(record: SubmissionRecord, code: String): AttachedCode =
        probe.around { delegate.writeCode(record, code) }

    override fun writeRunner(record: SubmissionRecord, code: String) = probe.around {
        delegate.writeRunner(record, code)
    }

    override fun writeStatement(record: SubmissionRecord, markdown: String) = probe.around {
        delegate.writeStatement(record, markdown)
    }

    override fun writeReadme(records: List<SubmissionRecord>) = probe.around { delegate.writeReadme(records) }

    override fun writeIndex(records: List<SubmissionRecord>) = probe.around { delegate.writeIndex(records) }

    override fun writeTagNotes(counts: List<TagCount>) = probe.around { delegate.writeTagNotes(counts) }
}
