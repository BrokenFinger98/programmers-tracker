package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Layer test over a real directory (dev rules §6.1). A run's code is the evidence part 4.3 pairs
 * into repair steps; what is pinned here is that each run leaves exactly one readable line.
 */
class RunLogTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `a run leaves one line with its name, language and full code`() {
        val run = aRun()

        log().append(run, "select 1\n")

        val line = lines().single()
        line["recordId"] shouldBe run.recordId()
        line["language"] shouldBe "java"
        line["code"] shouldBe "select 1\n"
    }

    /** Verdicts and messages live in the submission log; a second copy would disagree with it. */
    @Test
    fun `the line holds exactly the four keys the submission log cannot supply`() {
        log().append(aRun(), "a")

        rawLines().single().keys shouldBe setOf("recordId", "language", "codeFetchedAt", "code")
    }

    @Test
    fun `the instant the code was fetched is recorded from the clock`() {
        log().append(aRun(), "a")

        lines().single()["codeFetchedAt"] shouldBe "2026-10-03T15:21:02.5+09:00"
    }

    /** The startup retry re-attaches a record whose correction never landed; it must not double. */
    @Test
    fun `attaching the same run twice leaves one line`() {
        val run = aRun()

        log().append(run, "a")
        log().append(run, "a")

        lines() shouldHaveSize 1
    }

    @Test
    fun `two runs with identical bytes at different times are two lines`() {
        val first = aRun(ts = "2026-10-03T15:21:02+09:00")
        val second = first.copy(ts = OffsetDateTime.parse("2026-10-03T15:23:52+09:00"))

        log().append(first, "same")
        log().append(second, "same")

        lines() shouldHaveSize 2
    }

    @Test
    fun `a submit writes no run line — it owns an attempt file instead`() {
        log().append(aSubmissionRecord(action = GradingAction.SUBMIT), "x")

        Files.exists(layout().runLog(120804, TITLE)) shouldBe false
    }

    @Test
    fun `a torn last line is healed rather than glued to the next`() {
        val file = layout().runLog(120804, TITLE)
        Files.createDirectories(file.parent)
        Files.writeString(file, "{\"recordId\":\"torn")

        log().append(aRun(), "a")

        val written = Files.readAllLines(file)
        written.first() shouldBe "{\"recordId\":\"torn"
        written.last().startsWith("{\"recordId\":\"2026") shouldBe true
    }

    /** A crash can cut this very run's line short; the retry must still write it, whole. */
    @Test
    fun `a torn prefix of the same run does not count as already written`() {
        val run = aRun()
        val file = layout().runLog(120804, TITLE)
        Files.createDirectories(file.parent)
        Files.writeString(file, "{\"recordId\":\"${run.recordId()}\",\"language\":\"ja")

        log().append(run, "a")

        Files.readAllLines(file) shouldHaveSize 2
        lines().single()["code"] shouldBe "a"
    }

    // Appended to, never through a link (#361) ------------------------------------------------------

    /**
     * Measured in #354's review: a run's line was appended to the push token through a linked `runs.jsonl`. It is
     * refused now, and thrown, so the record keeps its code pending rather than claiming a line that was not kept.
     */
    @Test
    fun `a run log that is a link is refused, and the file it leads to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val runs = aLink(layout().runLog(120804, TITLE), token)

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { log().append(aRun(), "select 1\n") }
        }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        heard.single() shouldContain runs.toString()
        heard.single() shouldNotContain A_PUSH_CREDENTIAL
    }

    @Test
    fun `a problem directory that is a link gets no run log, and nothing is written where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/120804-두-수의-곱-구하기"), outside)

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { log().append(aRun(), "select 1\n") }
        }

        namesIn(outside).shouldBeEmpty()
        heard.single() shouldContain "is a symbolic link"
    }

    @Test
    fun `a run log that is a dangling link is refused, and nothing is created where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-run.jsonl")
        val runs = aLink(layout().runLog(120804, TITLE), nowhere)

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { log().append(aRun(), "select 1\n") }
        }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        heard.single() shouldContain runs.toString()
    }

    private fun aRun(ts: String = "2026-10-03T15:21:02+09:00", verdict: Verdict = Verdict.WRONG) = aSubmissionRecord(
        ts = OffsetDateTime.parse(ts),
        action = GradingAction.RUN,
        attempt = 0,
        verdict = verdict,
    )

    private fun layout() = RecordLayout(root)

    private fun log() = RunLog(layout(), CLOCK)

    private fun lines(): List<Map<String, String?>> = rawLines().map { line ->
        line.mapValues { (_, v) -> v.jsonPrimitive.content }
    }

    private fun rawLines(): List<JsonObject> =
        Files.readAllLines(layout().runLog(120804, TITLE)).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
        }

    private companion object {
        const val TITLE = "두 수의 곱 구하기"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-03T06:21:02.500Z"), ZoneOffset.ofHours(9))
    }
}
