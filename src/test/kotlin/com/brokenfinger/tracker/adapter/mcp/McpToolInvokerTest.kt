package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aCaptureKey
import com.brokenfinger.tracker.support.fixtures.aCatalogEntry
import com.brokenfinger.tracker.support.fixtures.aCatalogOf
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aSensorObservation
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTornRecordLine
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
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
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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

    // list_problems ------------------------------------------------------------------------

    /**
     * The answer no other tool can give (#100): the records alone cannot separate "never
     * tried" from "tried and failed", so `untouched` is the point of joining a catalog.
     */
    @Test
    fun `list_problems answers untouched for a catalogued problem with no submits`() {
        val invoker = invokerOver(catalog = aCatalogOf(aCatalogEntry(id = 120804), aCatalogEntry(id = 120803)))

        val payload = structured(invoker.call("list_problems", arguments("status" to "untouched")))

        payload["count"]!!.jsonPrimitive.int shouldBe 2
    }

    @Test
    fun `list_problems reports a passed problem with the submits it took`() {
        val invoker = invokerOver(
            aSubmissionRecord(lessonId = 120804, verdict = Verdict.WRONG, attempt = 1),
            aSubmissionRecord(lessonId = 120804, verdict = Verdict.PASS, attempt = 2, captureKey = aCaptureKey()),
            catalog = aCatalogOf(aCatalogEntry(id = 120804)),
        )

        val listed = structured(invoker.call("list_problems", JsonObject(emptyMap())))["problems"]!!
            .jsonArray.single().jsonObject

        listed["status"]!!.jsonPrimitive.content shouldBe "passed"
        listed["attempts"]!!.jsonPrimitive.int shouldBe 2
    }

    /** An unreadable argument is the client's mistake, said plainly rather than ignored. */
    @Test
    fun `list_problems refuses a level that is not a number`() {
        val result = invokerOver(catalog = aCatalogOf(aCatalogEntry())).call(
            "list_problems",
            arguments(
                "level" to "easy",
            ),
        )

        failed(result).shouldBeTrue()
        message(result).shouldContain("level")
    }

    @Test
    fun `list_problems refuses a status outside the three`() {
        val result = invokerOver(catalog = aCatalogOf(aCatalogEntry())).call(
            "list_problems",
            arguments(
                "status" to "nearly",
            ),
        )

        failed(result).shouldBeTrue()
        message(result).shouldContain("status")
    }

    // review_queue -----------------------------------------------------------------------------

    /**
     * The schedule travels with the facts that produced it (#132). The server schedules and
     * does not diagnose, which is only true if a reader can see the inputs and disagree.
     */
    @Test
    fun `review_queue answers what is due with the facts that scheduled it`() {
        val invoker = invokerOver(
            aSubmissionRecord(
                ts = OffsetDateTime.parse("2026-01-01T09:00:00+09:00"),
                verdict = Verdict.PASS,
                sensor = aSensorObservation(focusedSec = 612, sawQuestions = false),
            ),
            clock = Clock.fixed(Instant.parse("2026-03-10T00:00:00Z"), ZoneOffset.UTC),
        )

        val payload = structured(invoker.call("review_queue", JsonObject(emptyMap())))

        payload["count"]!!.jsonPrimitive.int shouldBe 1
        val item = payload["due"]!!.jsonArray.single().jsonObject
        item["confidence"]!!.jsonPrimitive.content shouldBe "high"
        item["attempts"]!!.jsonPrimitive.int shouldBe 1
        item["focusedSec"]!!.jsonPrimitive.int shouldBe 612
        item["dueAt"]!!.jsonPrimitive.content shouldBe "2026-03-02"
        item["overdueDays"]!!.jsonPrimitive.int shouldBe 8
    }

    /** Absent, not `false`: a record nothing observed must not read as one that saw no help. */
    @Test
    fun `review_queue omits sawQuestions when nothing was watching`() {
        val invoker = invokerOver(
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-01-01T09:00:00+09:00"), verdict = Verdict.PASS),
            clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC),
        )

        val item = structured(invoker.call("review_queue", JsonObject(emptyMap())))["due"]!!
            .jsonArray.single().jsonObject

        item.shouldNotContainKey("sawQuestions")
        item["confidence"]!!.jsonPrimitive.content shouldBe "medium"
    }

    @Test
    fun `review_queue caps at the requested limit`() {
        val invoker = invokerOver(
            aSubmissionRecord(lessonId = 1, verdict = Verdict.PASS),
            aSubmissionRecord(lessonId = 2, verdict = Verdict.PASS),
            clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC),
        )

        val payload = structured(invoker.call("review_queue", arguments("limit" to 1)))

        payload["count"]!!.jsonPrimitive.int shouldBe 1
    }

    /** Strict about a value we cannot honour (dev rules §4) — never quietly widened. */
    @Test
    fun `review_queue refuses a limit that is not a positive number`() {
        val invoker = invokerOver(aSubmissionRecord(verdict = Verdict.PASS))

        val answer = invoker.call("review_queue", arguments("limit" to 0))

        failed(answer).shouldBeTrue()
        message(answer).shouldContain("limit")
    }

    @Test
    fun `review_queue refuses an argument it does not have`() {
        val invoker = invokerOver(aSubmissionRecord(verdict = Verdict.PASS))

        val answer = invoker.call("review_queue", arguments("confidence" to "high"))

        failed(answer).shouldBeTrue()
        message(answer).shouldContain("confidence")
    }

    private fun invokerOver(
        vararg records: com.brokenfinger.tracker.domain.SubmissionRecord,
        catalog: com.brokenfinger.tracker.application.ProblemCatalog = anEmptyCatalog(),
        clock: Clock = Clock.systemUTC(),
    ): McpToolInvoker = McpToolInvoker(aRecordRepository(root).containing(*records).query(catalog, clock))

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
