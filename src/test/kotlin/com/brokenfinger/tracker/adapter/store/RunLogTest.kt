package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Layer test over a real directory (dev rules §6.1). A run's code is the evidence part 4.3 pairs
 * into repair steps; what is pinned here is that each run leaves exactly one readable line.
 */
class RunLogTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a run leaves one line with its name, verdict and full code`() {
        val run = aRun(verdict = Verdict.WRONG)

        log().append(run, "select 1\n")

        val line = lines().single()
        line["recordId"] shouldBe run.recordId()
        line["verdict"] shouldBe "WRONG"
        line["code"] shouldBe "select 1\n"
    }

    @Test
    fun `the failing case's message is kept, which is where a database error lives`() {
        val tuple = "(1054, \"Unknown column 'USER_ID' in 'field list'\")"
        val run = aRun(
            verdict = Verdict.COMPILE_ERROR,
            testcases = listOf(aTestcaseResult(passed = false, msg = tuple, runTime = null, returnedResult = false)),
        )

        log().append(run, "select USER_ID from x")

        lines().single()["failedMessage"] shouldBe tuple
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

        Files.readAllLines(file).last().startsWith("{\"recordId\":\"2026") shouldBe true
    }

    private fun aRun(
        ts: String = "2026-10-03T15:21:02+09:00",
        verdict: Verdict = Verdict.WRONG,
        testcases: List<TestcaseResult> =
            listOf(aTestcaseResult(passed = false, msg = null, runTime = null, returnedResult = true)),
    ) = aSubmissionRecord(
        ts = OffsetDateTime.parse(ts),
        action = GradingAction.RUN,
        attempt = 0,
        verdict = verdict,
        testcases = testcases,
    )

    private fun layout() = RecordLayout(root)

    private fun log() = RunLog(layout())

    private fun lines(): List<Map<String, String?>> =
        Files.readAllLines(layout().runLog(120804, TITLE)).filter { it.isNotBlank() }.map { line ->
            Json.parseToJsonElement(line).jsonObject.mapValues { (_, v) ->
                runCatching { v.jsonPrimitive.content }.getOrNull()
            }
        }

    private companion object {
        const val TITLE = "두 수의 곱 구하기"
    }
}
