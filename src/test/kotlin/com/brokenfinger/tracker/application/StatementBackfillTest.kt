package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The repair pass for problems solved before the server kept statements (#280).
 *
 * Everything asserted here is about **not fetching**: which problems are skipped without a
 * request, when the pass stops, and how many it is willing to ask for in one boot. A backfill
 * that fetches more than it needs is the thing development-rules §9.3 asks this tool not to be.
 *
 * Nothing sleeps — the pause is injected, the same shape `CommandLineGitSync` uses for its
 * backoff.
 */
class StatementBackfillTest {
    @TempDir
    lateinit var root: Path

    private val asked = mutableListOf<Long>()

    @Test
    fun `a problem with no statement gets one`() {
        record(aSubmissionRecord())

        val report = backfill(answering { StatementFetch.Fetched("the problem, as worded") }).runNow()

        report.filled shouldBe 1
        Files.readString(statementOf(120804)).trim() shouldBe "the problem, as worded"
    }

    /** The whole point of reading the disk first: a problem already filled costs no request. */
    @Test
    fun `a problem that already has one is never asked about`() {
        record(aSubmissionRecord())
        Files.createDirectories(statementOf(120804).parent)
        Files.writeString(statementOf(120804), "already here\n")

        val report = backfill(answering { StatementFetch.Fetched("replacement") }).runNow()

        asked.shouldBeEmpty()
        report.filled shouldBe 0
        Files.readString(statementOf(120804)).trim() shouldBe "already here"
    }

    @Test
    fun `an empty history asks nothing and reports nothing`() {
        val report = backfill(answering { StatementFetch.Fetched("x") }).runNow()

        asked.shouldBeEmpty()
        report shouldBe BackfillReport()
    }

    /** One request per problem, not per record — a problem solved in four languages is one page. */
    @Test
    fun `a problem recorded many times is asked about once`() {
        record(aSubmissionRecord(language = "java"))
        record(aSubmissionRecord(language = "python3"))
        record(aSubmissionRecord(language = "cpp"))

        backfill(answering { StatementFetch.Fetched("body") }).runNow()

        asked shouldContainExactly listOf(120804L)
    }

    /**
     * An expired session and a rate limit are shared by every remaining problem, so learning it
     * again for each of them is only rude — the discipline `attachPending` already follows.
     */
    @Test
    fun `a blocking answer stops the pass instead of asking everyone else`() {
        record(aSubmissionRecord(lessonId = 1))
        record(aSubmissionRecord(lessonId = 2))
        record(aSubmissionRecord(lessonId = 3))

        val report = backfill(answering { StatementFetch.Blocked }).runNow()

        asked.size shouldBe 1
        report.blocked.shouldBeTrue()
        report.filled shouldBe 0
    }

    /** One problem's failure says nothing about the next one's. */
    @Test
    fun `a failure for one problem does not stop the others`() {
        record(aSubmissionRecord(lessonId = 1))
        record(aSubmissionRecord(lessonId = 2))

        val failing = ProblemStatementSource { lessonId, _ ->
            asked += lessonId
            if (lessonId == 1L) StatementFetch.Unavailable("nope") else StatementFetch.Fetched("body")
        }

        val report = backfill(failing).runNow()

        asked.size shouldBe 2
        report.filled shouldBe 1
        report.failed shouldBe 1
        report.blocked.shouldBeFalse()
    }

    /**
     * Three hundred fetches on one start is what §9.3 is about. A backlog drains over boots
     * instead, and nobody is waiting on it.
     */
    @Test
    fun `it asks for no more than its budget in one boot`() {
        (1L..5L).forEach { record(aSubmissionRecord(lessonId = it)) }

        val report = backfill(answering { StatementFetch.Fetched("body") }, perBoot = 2).runNow()

        asked.size shouldBe 2
        report.filled shouldBe 2
    }

    /** Between fetches and not before the first — a single problem waits for nothing. */
    @Test
    fun `it pauses between fetches, never before the first`() {
        var paused = 0
        record(aSubmissionRecord(lessonId = 1))
        record(aSubmissionRecord(lessonId = 2))

        backfill(answering { StatementFetch.Fetched("body") }, pause = { paused++ }).runNow()

        paused shouldBe 1
    }

    private fun answering(answer: (Long) -> StatementFetch) = ProblemStatementSource { lessonId, _ ->
        asked += lessonId
        answer(lessonId)
    }

    private fun backfill(
        source: ProblemStatementSource,
        perBoot: Int = 20,
        pause: suspend () -> Unit = {},
    ): StatementBackfill {
        val store = JsonlRecordStore.under(root)
        return StatementBackfill(
            store = store,
            statements = { lessonId, title ->
                runCatching { Files.readString(RecordLayout(root).statementFile(lessonId, title)) }.getOrNull()
            },
            source = source,
            artifacts = FileDerivedArtifacts(root, store),
            perBoot = perBoot,
            pause = pause,
        )
    }

    private fun StatementBackfill.runNow(): BackfillReport = runBlocking { run() }

    private fun record(record: SubmissionRecord) =
        JsonlRecordStore.under(root).append(SubmissionRecordJson.encode(record))

    private fun statementOf(lessonId: Long): Path =
        RecordLayout(root).statementFile(lessonId, aSubmissionRecord().title)
}
