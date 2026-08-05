package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTornRecordLine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.OffsetDateTime

/** Contract tests per tool (design §10), each against a record repository on disk. */
class McpToolInvokerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `submissions answers the whole history`() {
        val invoker = invokerOver(
            aSubmissionRecord(lessonId = 120804, verdict = Verdict.WRONG),
            aSubmissionRecord(lessonId = 131528, verdict = Verdict.PASS),
        )

        val payload = structured(invoker.call("submissions", JsonObject(emptyMap())))

        payload["count"]!!.jsonPrimitive.int shouldBe 2
        payload["submissions"]!!.jsonArray.size shouldBe 2
    }

    @Test
    fun `submissions narrows by verdict`() {
        val invoker = invokerOver(
            aSubmissionRecord(verdict = Verdict.WRONG),
            aSubmissionRecord(verdict = Verdict.PASS),
        )

        val payload = structured(invoker.call("submissions", arguments("verdict" to "PASS")))

        payload["count"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `submissions narrows by date`() {
        val invoker = invokerOver(
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-07-20T10:00:00+09:00")),
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-04T10:00:00+09:00")),
        )

        structured(invoker.call("submissions", arguments("since" to "2026-08-01")))["count"]!!
            .jsonPrimitive.int shouldBe 1
    }

    /** The shape a user has on day one. */
    @Test
    fun `submissions on an empty record repository answers zero, not an error`() {
        val result = invokerOver().call("submissions", JsonObject(emptyMap()))

        failed(result).shouldBeFalse()
        structured(result)["count"]!!.jsonPrimitive.int shouldBe 0
    }

    @Test
    fun `submissions omits the heavy fields, which get_problem carries instead`() {
        val invoker = invokerOver(aSubmissionRecord(errorText = "boom"))

        val first = structured(invoker.call("submissions", JsonObject(emptyMap())))["submissions"]!!
            .jsonArray.first().jsonObject

        first.shouldNotContainKey("testcases")
        first.shouldNotContainKey("errorText")
    }

    @Test
    fun `get_problem answers with every attempt against the lesson`() {
        val invoker = invokerOver(
            aSubmissionRecord(lessonId = 120804, attempt = 1),
            aSubmissionRecord(lessonId = 120804, attempt = 2),
            aSubmissionRecord(lessonId = 131528, attempt = 1),
        )

        val payload = structured(invoker.call("get_problem", arguments("lessonId" to 120804)))

        payload["submissionCount"]!!.jsonPrimitive.int shouldBe 2
        payload["submissions"]!!.jsonArray.first().jsonObject["testcases"]!!.jsonArray.size shouldBe 1
    }

    /** A problem with no attempts recorded — the other day-one shape. */
    @Test
    fun `get_problem answers an unrecorded lesson with an empty history and no title`() {
        val result = invokerOver(aSubmissionRecord(lessonId = 131528)).call(
            "get_problem",
            arguments("lessonId" to 120804),
        )

        failed(result).shouldBeFalse()
        val payload = structured(result)
        payload["submissionCount"]!!.jsonPrimitive.int shouldBe 0
        payload.shouldNotContainKey("title")
    }

    @Test
    fun `get_problem takes a quoted number, because models routinely quote them`() {
        val invoker = invokerOver(aSubmissionRecord(lessonId = 120804))

        structured(invoker.call("get_problem", arguments("lessonId" to "120804")))["submissionCount"]!!
            .jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `stats counts by each group it offers`() {
        val invoker = invokerOver(
            aSubmissionRecord(verdict = Verdict.PASS, language = "java"),
            aSubmissionRecord(verdict = Verdict.WRONG, language = "java"),
        )

        listOf("verdict", "language", "problem").forEach { group ->
            val payload = structured(invoker.call("stats", arguments("groupBy" to group)))

            payload["groupBy"]!!.jsonPrimitive.content shouldBe group
            payload["total"]!!.jsonPrimitive.int shouldBe 2
        }
    }

    @Test
    fun `stats reports an unresolved grading as a bucket with no key`() {
        val invoker = invokerOver(
            aSubmissionRecord(verdict = Verdict.PASS),
            aSubmissionRecord(outcome = Outcome.INCOMPLETE, verdict = null),
        )

        val entries = structured(invoker.call("stats", arguments("groupBy" to "verdict")))["entries"]!!.jsonArray

        entries.last().jsonObject.shouldNotContainKey("key")
    }

    @Test
    fun `every tool keeps answering when the log ends in a torn line`() {
        aRecordRepository(root).containing(aSubmissionRecord()).tornBy(aTornRecordLine())
        val invoker = McpToolInvoker(aRecordRepository(root).query())

        failed(invoker.call("submissions", JsonObject(emptyMap()))).shouldBeFalse()
        failed(invoker.call("get_problem", arguments("lessonId" to 120804))).shouldBeFalse()
        failed(invoker.call("stats", arguments("groupBy" to "verdict"))).shouldBeFalse()
    }

    @Test
    fun `returns the same JSON as text as well, for clients without structured content`() {
        val result = invokerOver(aSubmissionRecord()).call("stats", arguments("groupBy" to "verdict"))

        val text = result["content"]!!.jsonArray.single().jsonObject
        text["type"]!!.jsonPrimitive.content shouldBe "text"
        text["text"]!!.jsonPrimitive.content shouldBe structured(result).toString()
    }

    /** An unknown tool is a protocol error: no rewording of the arguments will make it exist. */
    @Test
    fun `refuses an unknown tool as a protocol error, and names what it does expose`() {
        val thrown = shouldThrow<McpFailure> { invokerOver().call("warmup_plan", JsonObject(emptyMap())) }

        thrown.code shouldBe McpErrors.INVALID_PARAMS
        thrown.message.shouldContain("submissions")
        thrown.message.shouldContain("stats")
    }

    @Test
    fun `refuses a call with no tool name`() {
        shouldThrow<McpFailure> { invokerOver().call(null, JsonObject(emptyMap())) }
            .code shouldBe McpErrors.INVALID_PARAMS
    }

    // A bad argument is a tool execution error instead, because a model can fix it and retry.
    @Test
    fun `reports a date it cannot read as a correctable tool error`() {
        val result = invokerOver().call("submissions", arguments("since" to "last tuesday"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("2026-08-01")
    }

    @Test
    fun `reports a verdict it does not know as a correctable tool error`() {
        val result = invokerOver().call("submissions", arguments("verdict" to "ACCEPTED"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("PASS")
    }

    @Test
    fun `reports a group it does not know as a correctable tool error`() {
        val result = invokerOver().call("stats", arguments("groupBy" to "tag"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("verdict")
    }

    @Test
    fun `reports a missing required argument`() {
        failed(invokerOver().call("stats", JsonObject(emptyMap()))).shouldBeTrue()
        failed(invokerOver().call("get_problem", JsonObject(emptyMap()))).shouldBeTrue()
    }

    @Test
    fun `reports a lesson id that is not a number`() {
        val result = invokerOver().call("get_problem", arguments("lessonId" to "the first one"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("whole number")
    }

    @Test
    fun `reports a lesson id that is not positive`() {
        failed(invokerOver().call("get_problem", arguments("lessonId" to 0))).shouldBeTrue()
    }

    /** The schemas promise `additionalProperties: false`, so the server keeps that promise. */
    @Test
    fun `refuses an argument the schema does not declare, rather than answering a narrower question`() {
        val result = invokerOver().call("stats", arguments("groupBy" to "verdict", "limit" to 10))

        failed(result).shouldBeTrue()
        message(result).shouldContain("limit")
    }

    private fun invokerOver(vararg records: com.brokenfinger.tracker.domain.SubmissionRecord): McpToolInvoker =
        McpToolInvoker(aRecordRepository(root).containing(*records).query())

    private fun arguments(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, value)
                is Long -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private fun structured(result: JsonObject): JsonObject = result["structuredContent"]!!.jsonObject

    private fun failed(result: JsonObject): Boolean = result["isError"]!!.jsonPrimitive.booleanOrNull!!

    private fun message(result: JsonObject): String =
        result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
}
