package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aQuietGitSync
import com.brokenfinger.tracker.support.fixtures.aSettledCapture
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * Layer test for stage 3 (dev rules §6.1). Real files under a [TempDir], a real submission
 * log, a real artifact writer — only the page fetch is a double, because the network is the
 * one thing stage 3 may not have.
 *
 * Half of these tests are failure-path tests on purpose (dev rules §6.3). The pipeline's
 * whole claim is that a fetch failure is survivable: the verdict can never be recaptured
 * (protocol doc §11) while the code can be fetched again (protocol doc §10), so every
 * non-[CodeFetch.Fetched] branch has to leave the record exactly where it was.
 *
 * Nothing here sleeps and no clock is read except the injected one.
 */
class CodeAttachmentTest {
    @TempDir
    lateinit var root: Path

    // Attaching ----------------------------------------------------------------------------

    /**
     * The runner rides the attachment (#37): fresh code plus the captured examples produce
     * `RunnerTest.java` in the same pass that wrote `Solution.java`.
     */
    @Test
    fun `an attachment regenerates the runner from the captured examples`() = runBlocking<Unit> {
        examplesOnDisk("""[{"input":"6, 7","expected":"42"}]""")
        val record = stored(aPending(attempt = 2))

        attachment(fetches(CODE_V2)).attach(record)

        Files.readString(problemDirectory().resolve("RunnerTest.java")) shouldContain "solution(6, 7)"
    }

    /** No captured examples refuses — and removes a stale runner rather than leaving a lie. */
    @Test
    fun `no examples means no runner, and a stale one is removed`() = runBlocking<Unit> {
        Files.createDirectories(problemDirectory())
        Files.writeString(problemDirectory().resolve("RunnerTest.java"), "// stale")
        val record = stored(aPending(attempt = 2))

        attachment(fetches(CODE_V2)).attach(record)

        Files.exists(problemDirectory().resolve("RunnerTest.java")) shouldBe false
    }

    @Test
    fun `a fetched submit writes its solution file, its attempt copy and clears the mark`() = runBlocking<Unit> {
        val record = stored(aPending(attempt = 2))

        attachment(fetches(CODE_V2)).attach(record) shouldBe AttachOutcome.ATTACHED

        Files.readString(problemDirectory().resolve("Solution.java")) shouldBe CODE_V2 + "\n"
        Files.readString(problemDirectory().resolve("attempts/002.java")) shouldBe CODE_V2 + "\n"
        resolved().codePath shouldBe "problems/120804-두-수의-곱-구하기/attempts/002.java"
        resolved().isCodeAttached() shouldBe true
    }

    @Test
    fun `a run refreshes the solution file and points at it, owning no attempt copy`() = runBlocking<Unit> {
        val record = stored(aPending(action = GradingAction.RUN, attempt = 0))

        attachment(fetches(CODE_V1)).attach(record) shouldBe AttachOutcome.ATTACHED

        resolved().codePath shouldBe "problems/120804-두-수의-곱-구하기/Solution.java"
        Files.exists(problemDirectory().resolve("attempts")) shouldBe false
    }

    @Test
    fun `the diff against the previous attempt lands on the corrected record`() = runBlocking<Unit> {
        stored(aPending(attempt = 1, captureKey = CaptureKey("aaaa000000000001")))
        val second = stored(aPending(attempt = 2))
        attachment(fetches(CODE_V1)).attach(first())

        attachment(fetches(CODE_V2)).attach(second)

        val diff = resolved(second.captureKey).diffFromPrev.shouldNotBeNull()
        diff shouldContain "-        return num1 * num2;"
        diff shouldContain "+        return (long) num1 * num2;"
    }

    @Test
    fun `the problem README is regenerated from the lesson's records`() = runBlocking<Unit> {
        val record = stored(aPending(attempt = 2))

        attachment(fetches(CODE_V2)).attach(record)

        val readme = Files.readString(problemDirectory().resolve("README.md"))
        readme shouldContain "lessonId: 120804"
        readme shouldContain "| 2 |"
    }

    // Failure paths — the record must survive all of them ------------------------------------

    @Test
    fun `an expired session leaves the record intact, still pending, and writes nothing`() = runBlocking<Unit> {
        val record = stored(aPending(attempt = 2))

        attachment { _, _ -> CodeFetch.Unauthenticated }.attach(record) shouldBe AttachOutcome.BLOCKED

        untouched(record)
    }

    @Test
    fun `a rate limit leaves the record intact, still pending, and writes nothing`() = runBlocking<Unit> {
        val record = stored(aPending(attempt = 2))

        attachment { _, _ -> CodeFetch.RateLimited }.attach(record) shouldBe AttachOutcome.BLOCKED

        untouched(record)
    }

    @Test
    fun `a page with no saved code leaves the record intact, still pending, and writes nothing`() = runBlocking<Unit> {
        val record = stored(aPending(attempt = 2))

        attachment { _, _ -> CodeFetch.Unavailable("no code input") }.attach(record) shouldBe AttachOutcome.DEFERRED

        untouched(record)
    }

    @Test
    fun `a fetcher that throws leaves the record intact, still pending, and writes nothing`() = runBlocking<Unit> {
        val record = stored(aPending(attempt = 2))

        attachment { _, _ -> error("the page fetch blew up") }.attach(record) shouldBe AttachOutcome.DEFERRED

        untouched(record)
    }

    // What the appended correction may not disturb --------------------------------------------

    @Test
    fun `the correction repeats the attempt number, so the counter cannot move`() = runBlocking<Unit> {
        val record = stored(aPending(attempt = 2))

        attachment(fetches(CODE_V2)).attach(record)

        AttemptAuthority.from(store().read()).latestOf(120804) shouldBe 2
    }

    @Test
    fun `a replay is still dropped after the correction, so the dedup index is intact`() = runBlocking<Unit> {
        val capture = aSettledCapture()
        val record = writer().write(capture).shouldNotBeNull()
        attachment(fetches(CODE_V2)).attach(record)

        writer().replay(capture) shouldBe null

        keysInLog() shouldContainExactly listOf(record.captureKey, record.captureKey)
    }

    // The startup pass ------------------------------------------------------------------------

    @Test
    fun `a pass attaches every record the log still resolves to pending`() = runBlocking<Unit> {
        stored(aPending(attempt = 1, captureKey = CaptureKey("aaaa000000000001")))
        stored(aPending(attempt = 2))

        attachment(fetches(CODE_V2)).attachPending() shouldBe AttachReport(attached = 2)

        RecordHistory.of(store().read()).count { it.codePending } shouldBe 0
    }

    @Test
    fun `a second pass finds nothing left pending, which is what makes it safe on every boot`() = runBlocking<Unit> {
        stored(aPending(attempt = 2))
        attachment(fetches(CODE_V2)).attachPending()
        val afterFirst = Files.readAllBytes(problemDirectory().resolve("attempts/002.java")).toList()

        attachment(fetches(CODE_V2)).attachPending() shouldBe AttachReport()

        Files.readAllBytes(problemDirectory().resolve("attempts/002.java")).toList() shouldBe afterFirst
    }

    @Test
    fun `a pass stops at an expired session rather than asking again for every record`() = runBlocking<Unit> {
        stored(aPending(attempt = 1, captureKey = CaptureKey("aaaa000000000001")))
        stored(aPending(attempt = 2))
        var asked = 0
        val expired = CodeFetcher { _, _ ->
            asked++
            CodeFetch.Unauthenticated
        }

        attachment(expired).attachPending() shouldBe AttachReport(blocked = 1)

        asked shouldBe 1
    }

    @Test
    fun `an empty log needs no pass at all`() = runBlocking<Unit> {
        attachment(fetches(CODE_V2)).attachPending() shouldBe AttachReport()
    }

    // Harness ----------------------------------------------------------------------------------

    private fun untouched(record: SubmissionRecord) {
        resolved() shouldBe record
        resolved().codePending shouldBe true
        Files.exists(problemDirectory()) shouldBe false
        store().read().size shouldBe 1
    }

    private fun aPending(
        action: GradingAction = GradingAction.SUBMIT,
        attempt: Int = 2,
        captureKey: CaptureKey = CaptureKey("7f4afc0c3bbc82c8"),
    ) = aSubmissionRecord(
        action = action,
        attempt = attempt,
        captureKey = captureKey,
        codePath = null,
        codePending = true,
        diffFromPrev = null,
    )

    private fun stored(record: SubmissionRecord): SubmissionRecord {
        store().append(SubmissionRecordJson.encode(record))
        return record
    }

    private fun first(): SubmissionRecord = RecordHistory.of(store().read()).first()

    private fun resolved(key: CaptureKey = CaptureKey("7f4afc0c3bbc82c8")): SubmissionRecord =
        RecordHistory.of(store().read()).single { it.captureKey == key }

    private fun keysInLog(): List<CaptureKey> = store().read().map { SubmissionRecordJson.decode(it.line).captureKey }

    private fun attachment(fetcher: CodeFetcher) =
        CodeAttachment(fetcher, store(), FileDerivedArtifacts(root, store()), Dispatchers.Unconfined)

    private fun fetches(code: String) = CodeFetcher { _, _ -> CodeFetch.Fetched(code) }

    // Committing is switched off: these tests are about what attachment does to a record and
    // its files, and a real git here would only add a second reason for them to fail.
    private fun writer() = RecordWriter.of(
        store = store(),
        rawLog = FileRawSessionLog(root.resolve(".ps/raw")),
        rawAttemptPath = AttemptRawPath(RecordLayout(root)::rawAttemptFile),
        recordRoot = root,
        git = aQuietGitSync(),
        submissionLog = RecordLayout(root).submissionLog(),
        clock = Clock.fixed(Instant.parse("2026-08-04T05:23:01Z"), ZoneOffset.UTC),
        writerDispatcher = Dispatchers.Unconfined,
    )

    private fun examplesOnDisk(json: String) {
        Files.createDirectories(problemDirectory())
        Files.writeString(problemDirectory().resolve("examples.json"), json)
    }

    private fun store(): RecordStore = JsonlRecordStore.under(root)

    private fun problemDirectory(): Path = root.resolve("problems/120804-두-수의-곱-구하기")

    private companion object {
        val CODE_V1 =
            """
            class Solution {
                public long solution(int num1, int num2) {
                    return num1 * num2;
                }
            }
            """.trimIndent()

        val CODE_V2 = CODE_V1.replace("return num1 * num2;", "return (long) num1 * num2;")
    }
}
