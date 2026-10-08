package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.CountingRecordStore
import com.brokenfinger.tracker.support.fixtures.aBrokenGradingCodes
import com.brokenfinger.tracker.support.fixtures.aCaptureKey
import com.brokenfinger.tracker.support.fixtures.aCatalogEntry
import com.brokenfinger.tracker.support.fixtures.aCatalogOf
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSensorObservation
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aSubmit
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import com.brokenfinger.tracker.support.fixtures.aTornRecordLine
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

        listOf("verdict", "language", "problem", "part", "level").forEach { group ->
            val payload = structured(invoker.call("stats", arguments("groupBy" to group)))

            payload["groupBy"]!!.jsonPrimitive.content shouldBe group
            payload["total"]!!.jsonPrimitive.int shouldBe 2
        }
    }

    @Test
    fun `stats by part also counts the problems in each part`() {
        val invoker = invokerOver(
            aSubmissionRecord(
                lessonId = 1,
                part = "SELECT",
                verdict = Verdict.WRONG,
                ts = OffsetDateTime.parse("2026-10-01T10:00:00+09:00"),
            ),
            aSubmissionRecord(
                lessonId = 1,
                part = "SELECT",
                verdict = Verdict.PASS,
                ts = OffsetDateTime.parse("2026-10-01T10:05:00+09:00"),
            ),
        )

        val entry = structured(invoker.call("stats", arguments("groupBy" to "part")))["entries"]!!
            .jsonArray.single().jsonObject

        entry["count"]!!.jsonPrimitive.int shouldBe 2
        entry["attempted"]!!.jsonPrimitive.int shouldBe 1
        entry["passed"]!!.jsonPrimitive.int shouldBe 1
        entry["passedFirstSubmit"]!!.jsonPrimitive.int shouldBe 0
        entry["runsBeforePass"]!!.jsonPrimitive.double shouldBe 0.0
    }

    /** A median is a number on the wire, not a string a reader has to parse. */
    @Test
    fun `stats by part reports the median of the runs before the first pass`() {
        val invoker = invokerOver(
            aRun(at = "2026-10-01T09:58:00+09:00"),
            aRun(at = "2026-10-01T09:59:00+09:00"),
            aSubmissionRecord(verdict = Verdict.PASS, ts = OffsetDateTime.parse("2026-10-01T10:00:00+09:00")),
        )

        val runs = structured(invoker.call("stats", arguments("groupBy" to "part")))["entries"]!!
            .jsonArray.single().jsonObject["runsBeforePass"]!!.jsonPrimitive

        runs.double shouldBe 2.0
        runs.isString.shouldBeFalse()
    }

    /** Absent, not zero: nothing in the bucket passed, so there is no median to report. */
    @Test
    fun `stats by level leaves out runs-before-pass when nothing passed`() {
        val invoker = invokerOver(aSubmissionRecord(level = 2, verdict = Verdict.WRONG))

        val entry = structured(invoker.call("stats", arguments("groupBy" to "level")))["entries"]!!
            .jsonArray.single().jsonObject

        entry["key"]!!.jsonPrimitive.content shouldBe "2"
        entry["attempted"]!!.jsonPrimitive.int shouldBe 1
        entry["passed"]!!.jsonPrimitive.int shouldBe 0
        entry.shouldNotContainKey("runsBeforePass")
    }

    /** On these groupings a problem "passed" inside the bucket would mean nothing, so they carry counts only. */
    @Test
    fun `stats by verdict, language or problem carries counts only`() {
        val invoker = invokerOver(aSubmissionRecord())

        listOf("verdict", "language", "problem").forEach { group ->
            val entry = structured(invoker.call("stats", arguments("groupBy" to group)))["entries"]!!
                .jsonArray.single().jsonObject

            entry.keys.none { it in PROGRESS_FIELDS }.shouldBeTrue()
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

    /**
     * The counts are the basis of every claim about the learner, so the client has to be able
     * to see that the denominator is incomplete (#169). Before this the holes were announced
     * once, in a warning on the day, and nothing afterwards said the history was partial.
     */
    @Test
    fun `every tool says so when gradings exist that no record represents`() {
        val invoker = invokerOverAHole(aSubmissionRecord())

        // A pass that was lost is a problem review_queue will never schedule and a reading
        // slow_passes cannot rank — every tool reads the same history (#187).
        everyToolCall().forEach { (tool, args) ->
            structured(invoker.call(tool, args))["incompleteHistory"]!!
                .jsonObject["lessonsWithOrphanedFrames"]!!.jsonPrimitive.int shouldBe 1
        }
    }

    /**
     * A client that cuts a large answer cuts its end, so a warning at the end is the first thing lost (#356):
     * Claude Code caps a tool result at 25,000 tokens by default, and a `repair_steps` answer with long diffs
     * reaches it. The text is checked as well as the object because the text is what a model reads and what
     * gets cut — an object whose first key is right says nothing about the bytes if they were laid out another
     * way. Each check names every tool that fails it, with the key that led instead.
     */
    @Test
    fun `every tool puts incompleteHistory ahead of its payload, in the text a client cuts as well`() {
        val invoker = invokerOverAHole(aSubmissionRecord())
        val answers = everyToolCall().associate { (tool, args) -> tool to invoker.call(tool, args) }

        withClue("tools whose structuredContent leads with another key, and the key that led") {
            answers.mapValues { structured(it.value).keys.first() }.filterValues { it != "incompleteHistory" }
                .shouldBeEmpty()
        }
        withClue("tools whose text does not open with the whole warning, so a cut can reach it") {
            answers.filterValues { !message(it).startsWith(warningOpening(it)) }.keys.shouldBeEmpty()
        }
    }

    /**
     * The envelope `repair_steps` already had — the counts say how much of the list arrived — with the warning,
     * which says how much of the history did, ahead of it. `truncated` is written only when the list was cut.
     */
    @Test
    fun `repair_steps leads with the warning, then its counts, then the steps`() {
        val invoker = invokerOverAHole(*failedRuns(26))

        val cut = structured(invoker.call("repair_steps", JsonObject(emptyMap())))
        val whole = structured(invoker.call("repair_steps", arguments("limit" to 30)))

        cut.keys.toList() shouldBe listOf("incompleteHistory", "count", "total", "truncated", "steps")
        whole.keys.toList() shouldBe listOf("incompleteHistory", "count", "total", "steps")
    }

    /** The table above is only a claim about every tool while it holds every tool. */
    @Test
    fun `the table of calls names each tool the server offers`() {
        everyToolCall().map { it.first } shouldContainExactlyInAnyOrder McpToolCatalog.NAMES
    }

    @Test
    fun `stats says so when gradings exist that no record represents`() {
        val raw = FileRawSessionLog.under(root, Clock.systemUTC(), aStateDirectory(root))
        raw.orphaned(120802, """{"message":{"action":"submit","type":"testcase"}}""")
        raw.orphaned(120802, """{"message":{"action":"submit","type":"finish"}}""")
        raw.orphaned(181946, """{"message":{"action":"submit","type":"finish"}}""")
        val invoker = McpToolInvoker(aRecordRepository(root).containing(aSubmissionRecord()).query(raw = raw))

        val gaps = structured(invoker.call("stats", arguments("groupBy" to "verdict")))["incompleteHistory"]!!
            .jsonObject

        gaps["lessonsWithOrphanedFrames"]!!.jsonPrimitive.int shouldBe 2
        gaps["frames"]!!.jsonPrimitive.int shouldBe 3
        gaps["lessons"]!!.jsonArray.map { it.jsonPrimitive.long } shouldBe listOf(120802L, 181946L)
    }

    /** Absence is the signal, so a complete history must not carry the field on any tool. */
    @Test
    fun `no tool mentions gaps when there are none`() {
        val invoker = invokerOver(aSubmissionRecord())

        structured(invoker.call("stats", arguments("groupBy" to "verdict"))).shouldNotContainKey("incompleteHistory")
        structured(invoker.call("submissions", JsonObject(emptyMap()))).shouldNotContainKey("incompleteHistory")
        structured(invoker.call("review_queue", JsonObject(emptyMap()))).shouldNotContainKey("incompleteHistory")
    }

    @Test
    fun `every tool keeps answering when the log ends in a torn line`() {
        aRecordRepository(root).containing(aSubmissionRecord()).tornBy(aTornRecordLine())
        val invoker = McpToolInvoker(aRecordRepository(root).query())

        failed(invoker.call("submissions", JsonObject(emptyMap()))).shouldBeFalse()
        failed(invoker.call("get_problem", arguments("lessonId" to 120804))).shouldBeFalse()
        failed(invoker.call("stats", arguments("groupBy" to "verdict"))).shouldBeFalse()
        failed(invoker.call("repair_steps", JsonObject(emptyMap()))).shouldBeFalse()
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

    /**
     * A key is the client's text (#365). Joined raw, "a, b" read as two arguments and a newline broke the message;
     * quoted as a JSON string, each key is one item. It stays a tool error, so a model can drop the key and retry.
     */
    @Test
    fun `an unknown argument comes back quoted as one item, with what the tool takes`() {
        mapOf("a, b" to "\"a, b\"", "a\nb" to "\"a\\nb\"").forEach { (key, quoted) ->
            val result = invokerOver().call("stats", arguments("groupBy" to "verdict", key to 1))

            withClue("key = $quoted") {
                failed(result).shouldBeTrue()
                message(result) shouldBe "unknown argument(s): $quoted; stats takes groupBy"
            }
        }
    }

    /**
     * What a tool takes is named in the order its schema lists it, which is the order `tools/list` showed the client,
     * so the refusal reads like the tool's documentation. Sorted, `get_problem` would lead with `include` and
     * `list_problems` would put `status` before `tag`.
     */
    @Test
    fun `every tool refusing a key names what it takes, in its schema's order`() {
        TAKEN.keys shouldContainExactlyInAnyOrder McpToolCatalog.NAMES

        TAKEN.forEach { (tool, taken) ->
            withClue(tool) {
                message(invokerOver().call(tool, arguments("bogus" to 1))) shouldBe
                    "unknown argument(s): \"bogus\"; $tool takes $taken"
            }
        }
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

    // slow_passes ------------------------------------------------------------------------------

    @Test
    fun `slow_passes ranks passes by their slowest testcase`() {
        val invoker = invokerOver(
            passWith(lessonId = 1, times = listOf("70.0")),
            passWith(lessonId = 2, times = listOf("68.0", "1636.97")),
        )

        val payload = structured(invoker.call("slow_passes", JsonObject(emptyMap())))

        payload["count"]!!.jsonPrimitive.int shouldBe 2
        val first = payload["slow"]!!.jsonArray.first().jsonObject
        first["lessonId"]!!.jsonPrimitive.int shouldBe 2
        first["slowestMs"]!!.jsonPrimitive.double shouldBe 1636.97
        first["timedCases"]!!.jsonPrimitive.int shouldBe 2
    }

    @Test
    fun `slow_passes narrows to the threshold`() {
        val invoker = invokerOver(
            passWith(lessonId = 1, times = listOf("70.0")),
            passWith(lessonId = 2, times = listOf("1636.97")),
        )

        val payload = structured(invoker.call("slow_passes", arguments("thresholdMs" to 100)))

        payload["count"]!!.jsonPrimitive.int shouldBe 1
    }

    /** SQL sends no per-case timing, and a gap the reader cannot see reads as no gap (#134). */
    @Test
    fun `slow_passes reports how many passes had no timing at all`() {
        val invoker = invokerOver(
            passWith(lessonId = 1, times = listOf("70.0")),
            passWith(lessonId = 2, times = listOf(null)),
        )

        val payload = structured(invoker.call("slow_passes", JsonObject(emptyMap())))

        payload["untimed"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `slow_passes refuses a threshold that is not a positive number`() {
        val result = invokerOver().call("slow_passes", arguments("thresholdMs" to -1))

        failed(result).shouldBeTrue()
        message(result).shouldContain("thresholdMs")
    }

    @Test
    fun `slow_passes refuses an argument it does not have`() {
        val result = invokerOver().call("slow_passes", arguments("level" to 2))

        failed(result).shouldBeTrue()
        message(result).shouldContain("level")
    }

    // repair_steps -----------------------------------------------------------------------------

    @Test
    fun `repair_steps answers each failed grading with the attempt that followed it, newest first`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:01:00+09:00")
        val third = aRun(at = "2026-10-07T10:02:00+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second, third)
                .withRunCode(first, "a").withRunCode(second, "b").withRunCode(third, "c").query(),
        )

        val payload = structured(invoker.call("repair_steps", JsonObject(emptyMap())))

        payload["count"]!!.jsonPrimitive.int shouldBe 2
        payload["total"]!!.jsonPrimitive.int shouldBe 2
        payload.shouldNotContainKey("truncated")
        val newest = payload["steps"]!!.jsonArray.first().jsonObject
        newest["from"]!!.jsonObject["recordId"]!!.jsonPrimitive.content shouldBe second.recordId()
        newest["to"]!!.jsonObject["verdict"]!!.jsonPrimitive.content shouldBe "PASS"
        newest["diff"]!!.jsonPrimitive.content shouldContain "+c"
        newest["title"]!!.jsonPrimitive.content shouldBe "두 수의 곱 구하기"
        newest.shouldNotContainKey("diffTruncated")
    }

    @Test
    fun `repair_steps says why a step has no diff`() {
        val first = aRun(at = "2026-10-06T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(aRecordRepository(root).containing(first, second).withRunCode(second, "b").query())

        val step = structured(invoker.call("repair_steps", JsonObject(emptyMap())))["steps"]!!.jsonArray.single()

        step.jsonObject["noDiff"]!!.jsonPrimitive.content shouldBe "fromCodeUnknown"
        step.jsonObject.shouldNotContainKey("diff")
    }

    /** A diff cut at the cap is marked on its step, so a reader never has to parse the marker out of the text. */
    @Test
    fun `repair_steps marks a diff that was cut at the cap`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:01:00+09:00", verdict = Verdict.PASS)
        val written = (1..500).joinToString("\n") { "n$it" }
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second)
                .withRunCode(first, "a").withRunCode(second, written).query(),
        )

        val step = structured(invoker.call("repair_steps", JsonObject(emptyMap())))["steps"]!!
            .jsonArray.single().jsonObject

        step["diffTruncated"]!!.jsonPrimitive.booleanOrNull shouldBe true
        step["diff"]!!.jsonPrimitive.content shouldContain "diff truncated"
    }

    @Test
    fun `repair_steps on an empty record repository answers zero, not an error`() {
        val result = invokerOver().call("repair_steps", JsonObject(emptyMap()))

        failed(result).shouldBeFalse()
        val payload = structured(result)
        payload["count"]!!.jsonPrimitive.int shouldBe 0
        payload["total"]!!.jsonPrimitive.int shouldBe 0
        payload["steps"]!!.jsonArray.size shouldBe 0
        payload.shouldNotContainKey("truncated")
    }

    /** One argument-less call used to be able to return every step on record, which grows without bound. */
    @Test
    fun `repair_steps returns at most 20 steps by default, newest first, and says it cut the list`() {
        val runs = failedRuns(26)

        val payload = structured(invokerOver(*runs).call("repair_steps", JsonObject(emptyMap())))

        payload["count"]!!.jsonPrimitive.int shouldBe 20
        payload["total"]!!.jsonPrimitive.int shouldBe 25
        payload["truncated"]!!.jsonPrimitive.booleanOrNull shouldBe true
        val steps = payload["steps"]!!.jsonArray.map { it.jsonObject }
        steps.size shouldBe 20
        steps.first().correctionId() shouldBe runs.last().recordId()
        steps.last().correctionId() shouldBe runs[6].recordId()
    }

    @Test
    fun `repair_steps takes a limit above the default, and a list that holds every step is not truncated`() {
        val payload = structured(invokerOver(*failedRuns(26)).call("repair_steps", arguments("limit" to 30)))

        payload["count"]!!.jsonPrimitive.int shouldBe 25
        payload["total"]!!.jsonPrimitive.int shouldBe 25
        payload.shouldNotContainKey("truncated")
    }

    @Test
    fun `repair_steps takes a limit below the default`() {
        val payload = structured(invokerOver(*failedRuns(26)).call("repair_steps", arguments("limit" to 5)))

        payload["count"]!!.jsonPrimitive.int shouldBe 5
        payload["total"]!!.jsonPrimitive.int shouldBe 25
        payload["truncated"]!!.jsonPrimitive.booleanOrNull shouldBe true
    }

    /** `truncated` is `total > count`, and a limit that exactly fits is where that flips. */
    @Test
    fun `repair_steps with a limit equal to the number of steps is not truncated`() {
        val payload = structured(invokerOver(*failedRuns(4)).call("repair_steps", arguments("limit" to 3)))

        payload["count"]!!.jsonPrimitive.int shouldBe 3
        payload.shouldNotContainKey("truncated")
    }

    @Test
    fun `repair_steps narrows to one lesson, quoted or not`() {
        val invoker = invokerOver(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2),
        )

        val payload = structured(invoker.call("repair_steps", arguments("lessonId" to "2")))

        payload["count"]!!.jsonPrimitive.int shouldBe 1
        payload["steps"]!!.jsonArray.single().jsonObject["lessonId"]!!.jsonPrimitive.int shouldBe 2
    }

    @Test
    fun `repair_steps narrows by language`() {
        val invoker = invokerOver(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1, language = "java"),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 1, language = "java"),
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2, language = "python3"),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2, language = "python3"),
        )

        val steps = structured(invoker.call("repair_steps", arguments("language" to "python3")))["steps"]!!.jsonArray

        steps.single().jsonObject["language"]!!.jsonPrimitive.content shouldBe "python3"
    }

    @Test
    fun `repair_steps narrows by part, whatever its case`() {
        val invoker = invokerOver(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1).copy(part = "SELECT"),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 1).copy(part = "SELECT"),
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2).copy(part = "JOIN"),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2).copy(part = "JOIN"),
        )

        val steps = structured(invoker.call("repair_steps", arguments("part" to "select")))["steps"]!!.jsonArray

        steps.single().jsonObject["part"]!!.jsonPrimitive.content shouldBe "SELECT"
    }

    /** `since` bounds the correction, and the failure that started it may be older than `since`. */
    @Test
    fun `repair_steps narrows by when the later grading was recorded, not the earlier one`() {
        val before = aRun(at = "2026-10-06T10:00:00+09:00")
        val straddling = aRun(at = "2026-10-06T10:01:00+09:00")
        val invoker = invokerOver(
            before,
            straddling,
            aRun(at = "2026-10-07T10:00:00+09:00"),
            aRun(at = "2026-10-07T10:01:00+09:00"),
        )

        val payload = structured(invoker.call("repair_steps", arguments("since" to "2026-10-07")))

        payload["count"]!!.jsonPrimitive.int shouldBe 2
        payload["steps"]!!.jsonArray.last().jsonObject["from"]!!.jsonObject["recordId"]!!
            .jsonPrimitive.content shouldBe straddling.recordId()
    }

    /** A JSON null is how some clients say "not given": for each narrowing argument it is the key left out. */
    @Test
    fun `repair_steps reads a JSON null as no narrowing`() {
        val invoker = invokerOver(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2),
        )
        val nulls = buildJsonObject {
            put("since", JsonNull)
            put("language", JsonNull)
            put("part", JsonNull)
            put("lessonId", JsonNull)
        }

        structured(invoker.call("repair_steps", nulls))["count"]!!.jsonPrimitive.int shouldBe 2
    }

    /** Not a limit of zero and not an error: "not given" means the default applies. */
    @Test
    fun `repair_steps reads a JSON null limit as not given, which is the default`() {
        val nullLimit = buildJsonObject { put("limit", JsonNull) }

        val payload = structured(invokerOver(*failedRuns(26)).call("repair_steps", nullLimit))

        payload["count"]!!.jsonPrimitive.int shouldBe 20
        payload["total"]!!.jsonPrimitive.int shouldBe 25
    }

    @Test
    fun `repair_steps refuses a limit that is not a positive number`() {
        listOf(0, -3).forEach { limit ->
            val result = invokerOver().call("repair_steps", arguments("limit" to limit))

            failed(result).shouldBeTrue()
            message(result).shouldContain("limit")
        }
    }

    @Test
    fun `repair_steps refuses a limit that is not a number`() {
        val result = invokerOver().call("repair_steps", arguments("limit" to "many"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("limit")
    }

    /** `text()` would read a blank as absent and widen the question; the filter refuses it, in its own words. */
    @Test
    fun `repair_steps refuses a blank language or part rather than reading it as no narrowing`() {
        listOf("language", "part").forEach { name ->
            listOf("", "   ").forEach { blank ->
                val result = invokerOver().call("repair_steps", arguments(name to blank))

                failed(result).shouldBeTrue()
                message(result).shouldContain(name)
            }
        }
    }

    /** A JSON number is not the text of its digits: `language: 5` is a mistake to say so, not the language "5". */
    @Test
    fun `repair_steps accepts only a JSON string for a text argument`() {
        val notStrings = listOf<JsonElement>(
            JsonPrimitive(5),
            JsonPrimitive(true),
            buildJsonObject { },
            buildJsonArray { add("java") },
        )

        listOf("since", "language", "part").forEach { name ->
            notStrings.forEach { notText ->
                val result = invokerOver().call("repair_steps", buildJsonObject { put(name, notText) })

                failed(result).shouldBeTrue()
                message(result).shouldContain(name)
            }
        }
    }

    /** A blank `since` is a malformed date like any other, so the answer teaches the spelling. */
    @Test
    fun `repair_steps tells a model how to spell a since it got wrong, a blank one included`() {
        listOf("last tuesday", "", "  ").forEach { since ->
            val result = invokerOver().call("repair_steps", arguments("since" to since))

            failed(result).shouldBeTrue()
            message(result).shouldContain("since")
            message(result).shouldContain("2026-08-01")
        }
    }

    @Test
    fun `repair_steps refuses a lesson id that is not a positive whole number`() {
        val notNumber = invokerOver().call("repair_steps", arguments("lessonId" to "the first one"))
        val notPositive = invokerOver().call("repair_steps", arguments("lessonId" to 0))

        failed(notNumber).shouldBeTrue()
        message(notNumber).shouldContain("whole number")
        failed(notPositive).shouldBeTrue()
        message(notPositive).shouldContain("positive")
    }

    @Test
    fun `repair_steps refuses an argument it does not have`() {
        val result = invokerOver().call("repair_steps", arguments("verdict" to "WRONG"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("verdict")
    }

    /**
     * What the adapter or the filter refuses never reaches the query. The code store below throws if
     * anything asks it for code, and the query asks for every problem it assembles, so a refused
     * argument that got through would end this test as an IllegalStateException instead of a tool error.
     */
    @Test
    fun `repair_steps never hands the query an argument it has refused`() {
        val invoker = invokerOverBrokenCodes()

        listOf(
            arguments("limit" to 0),
            arguments("limit" to "many"),
            arguments("language" to " "),
            arguments("part" to ""),
            arguments("since" to "last tuesday"),
            arguments("lessonId" to "the first one"),
        ).forEach { refused -> failed(invoker.call("repair_steps", refused)).shouldBeTrue() }
    }

    /**
     * Past validation an IllegalArgumentException can only be an invariant of ours breaking, and
     * `executed` would hand it to the model as advice to correct arguments that were fine. It leaves
     * as a state fault instead, which `McpDispatcher` answers as an internal error.
     *
     * The seam is the code port. No record and no file can break the invariants behind repair steps
     * (the query groups by problem before it asks), but the query reads code for every problem it
     * assembles, so a store that throws what such an invariant would reaches the wrapper through the
     * real query over a real repository.
     */
    @Test
    fun `repair_steps reports a broken invariant behind valid arguments as a fault of ours, not as advice`() {
        val thrown = shouldThrow<IllegalStateException> {
            invokerOverBrokenCodes().call("repair_steps", JsonObject(emptyMap()))
        }

        thrown.message.shouldContain(INVARIANT)
        thrown.cause.shouldBeInstanceOf<IllegalArgumentException>()
    }

    // get_problem include ----------------------------------------------------------------------

    @Test
    fun `get_problem with include code puts each submit's code on it, and nothing on runs`() {
        val run = aRun(at = "2026-10-07T09:59:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(run, submit)
                .withSubmitCode(submit, "select 1\n").withRunCode(run, "select 0").query(),
        )

        val items = itemsOf(invoker.call("get_problem", includeArguments("code")))

        items.single { it.isSubmit() }["code"]!!.jsonPrimitive.content shouldBe "select 1\n"
        CODE_KEYS.forEach { items.single { !it.isSubmit() }.shouldNotContainKey(it) }
    }

    @Test
    fun `get_problem with include runs puts each run's code and its diff from the grading before it`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:05+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second)
                .withRunCode(first, "a")
                .withRunCode(second, "b", attachedAt = OffsetDateTime.parse("2026-10-07T10:00:06+09:00")).query(),
        )

        val items = itemsOf(invoker.call("get_problem", includeArguments("runs")))
        val newest = items.first()

        newest["code"]!!.jsonPrimitive.content shouldBe "b"
        newest["codeFetchedAt"]!!.jsonPrimitive.content shouldBe "2026-10-07T10:00:06+09:00"
        newest["diffFromPrevGrading"]!!.jsonPrimitive.content shouldContain "+b"
        newest.shouldNotContainKey("noDiff")
        newest.shouldNotContainKey("codeLate")
        newest.shouldNotContainKey("fromCodeLate")
        newest.shouldNotContainKey("diffTruncated")
    }

    /** The first grading in a language has nothing before it to be compared with: neither key, not even `noDiff`. */
    @Test
    fun `get_problem with include runs gives the first grading in a language no diff and no reason for none`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:05+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second).withRunCode(first, "a").withRunCode(second, "b").query(),
        )

        val oldest = itemsOf(invoker.call("get_problem", includeArguments("runs"))).last()

        oldest["code"]!!.jsonPrimitive.content shouldBe "a"
        oldest.shouldNotContainKey("diffFromPrevGrading")
        oldest.shouldNotContainKey("noDiff")
    }

    /** Absent is not empty: a run whose code was never kept has no `code`, and the step into it says why. */
    @Test
    fun `get_problem with include runs says why a run has no diff, and leaves out the code that was not kept`() {
        val before = aRun(at = "2026-10-06T10:00:00+09:00")
        val after = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(aRecordRepository(root).containing(before, after).withRunCode(after, "b").query())

        val items = itemsOf(invoker.call("get_problem", includeArguments("runs")))

        items.first()["noDiff"]!!.jsonPrimitive.content shouldBe "fromCodeUnknown"
        items.first().shouldNotContainKey("diffFromPrevGrading")
        CODE_KEYS.forEach { items.last().shouldNotContainKey(it) }
    }

    /**
     * The same race the steps report: code attached after the next grading was recorded may be that grading's.
     * The late code is the earlier item's and the diff that used it is the later item's, so the later item says
     * so too: `sameCode` beside it is the signature of the race, not of a solution that was left alone.
     */
    @Test
    fun `get_problem with include runs marks a run whose code was attached after the next grading`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:02+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second)
                .withRunCode(first, "b", attachedAt = second.ts.plusSeconds(1))
                .withRunCode(second, "b").query(),
        )

        val items = itemsOf(invoker.call("get_problem", includeArguments("runs")))

        items.last()["codeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
        items.last().shouldNotContainKey("fromCodeLate")
        items.first().shouldNotContainKey("codeLate")
        items.first()["noDiff"]!!.jsonPrimitive.content shouldBe "sameCode"
        items.first()["fromCodeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
    }

    /** The grading before it in its own language: two languages on one problem do not diff against each other. */
    @Test
    fun `get_problem with include runs diffs a run against the grading before it in its own language`() {
        val javaFirst = aRun(at = "2026-10-07T10:00:00+09:00", language = "java")
        val kotlinFirst = aRun(at = "2026-10-07T10:00:10+09:00", language = "kotlin")
        val javaSecond = aRun(at = "2026-10-07T10:00:20+09:00", language = "java")
        val kotlinSecond = aRun(at = "2026-10-07T10:00:30+09:00", language = "kotlin")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(javaFirst, kotlinFirst, javaSecond, kotlinSecond)
                .withRunCode(javaFirst, "j1").withRunCode(kotlinFirst, "k1")
                .withRunCode(javaSecond, "j2").withRunCode(kotlinSecond, "k2").query(),
        )

        val byCode = itemsOf(invoker.call("get_problem", includeArguments("runs")))
            .associateBy { it["code"]!!.jsonPrimitive.content }

        byCode["j2"]!!["diffFromPrevGrading"]!!.jsonPrimitive.content shouldContain "-j1"
        byCode["k2"]!!["diffFromPrevGrading"]!!.jsonPrimitive.content shouldContain "-k1"
        byCode["j1"]!!.shouldNotContainKey("diffFromPrevGrading")
        byCode["k1"]!!.shouldNotContainKey("diffFromPrevGrading")
    }

    /** A grading is a grading whichever way it was pressed: the run after a submit is diffed against its code. */
    @Test
    fun `get_problem with include runs diffs a run against the submit before it`() {
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.WRONG)
        val run = aRun(at = "2026-10-07T10:01:00+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(submit, run)
                .withSubmitCode(submit, "first try\n").withRunCode(run, "second try").query(),
        )

        val items = itemsOf(invoker.call("get_problem", includeArguments("runs")))

        val diff = items.single { !it.isSubmit() }["diffFromPrevGrading"]!!.jsonPrimitive.content
        diff shouldContain "-first try"
        diff shouldContain "+second try"
        items.single { !it.isSubmit() }.shouldNotContainKey("fromCodeLate")
        items.single { it.isSubmit() }.shouldNotContainKey("code")
    }

    /** The flag keeps the name a repair step gives it, so a reader learns one word for one thing. */
    @Test
    fun `get_problem with include runs marks a diff that was cut at the cap`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:01:00+09:00", verdict = Verdict.PASS)
        val written = (1..500).joinToString("\n") { "n$it" }
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second)
                .withRunCode(first, "a").withRunCode(second, written).query(),
        )

        val newest = itemsOf(invoker.call("get_problem", includeArguments("runs"))).first()

        newest["diffTruncated"]!!.jsonPrimitive.booleanOrNull shouldBe true
        newest["diffFromPrevGrading"]!!.jsonPrimitive.content shouldContain "diff truncated"
    }

    /** Absent is not empty: an attempt file that is gone leaves no `code`, and nothing says the code was blank. */
    @Test
    fun `get_problem with include code leaves out the code of a submit whose file is gone`() {
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(aRecordRepository(root).containing(submit).query())

        itemsOf(invoker.call("get_problem", includeArguments("code"))).single().shouldNotContainKey("code")
    }

    @Test
    fun `get_problem with both includes puts submit code on submits and run code on runs, in either order`() {
        val run = aRun(at = "2026-10-07T09:59:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(run, submit)
                .withSubmitCode(submit, "select 1\n").withRunCode(run, "select 0").query(),
        )

        val answer = invoker.call("get_problem", includeArguments("code", "runs"))

        val items = itemsOf(answer)
        items.single { it.isSubmit() }["code"]!!.jsonPrimitive.content shouldBe "select 1\n"
        items.single { !it.isSubmit() }["code"]!!.jsonPrimitive.content shouldBe "select 0"
        invoker.call("get_problem", includeArguments("runs", "code")) shouldBe answer
    }

    /** `include` adds to what a client already reads: no key, value or position of the default answer moves. */
    @Test
    fun `get_problem with include keeps every key and value of the answer without it`() {
        val run = aRun(at = "2026-10-07T09:59:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(run, submit)
                .withSubmitCode(submit, "select 1\n").withRunCode(run, "select 0").query(),
        )

        val plain = structured(invoker.call("get_problem", arguments("lessonId" to 120804)))
        val enriched = structured(invoker.call("get_problem", includeArguments("code", "runs")))

        enriched.keys.toList() shouldBe plain.keys.toList()
        (enriched - "submissions") shouldBe (plain - "submissions")
        enriched["submissions"]!!.jsonArray.zip(plain["submissions"]!!.jsonArray).forEach { (with, without) ->
            with.jsonObject.keys.toList().take(without.jsonObject.size) shouldBe without.jsonObject.keys.toList()
            with.jsonObject.filterKeys { it in without.jsonObject.keys } shouldBe without.jsonObject
        }
    }

    /**
     * The default answer's shape written out, not compared with another answer from the same code: the
     * include tests above would not notice a key that leaked into both. The problem's keys come in this
     * order, and each item is exactly its record's own encoding — no code key until `include` asks for one.
     */
    @Test
    fun `get_problem without include has exactly the problem's keys, and each item exactly its record's`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:01:00+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(run, submit).withRunCode(run, "a").withSubmitCode(submit, "b").query(),
        )

        val result = invoker.call("get_problem", arguments("lessonId" to 120804))

        structured(result).keys.toList() shouldBe listOf(
            "lessonId",
            "title",
            "level",
            "part",
            "acceptanceRate",
            "tags",
            "submissionCount",
            "runCount",
            "submissions",
        )
        val records = listOf(submit, run)
        itemsOf(result).map { it.keys.toList() } shouldBe records.map { McpRecordJson.full(it).keys.toList() }
    }

    /** The default answer must not change shape for clients that never ask for code. */
    @Test
    fun `get_problem without include carries no code`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:01:00+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(run, submit).withRunCode(run, "a").withSubmitCode(submit, "b").query(),
        )

        itemsOf(invoker.call("get_problem", arguments("lessonId" to 120804))).forEach { item ->
            CODE_KEYS.forEach { item.shouldNotContainKey(it) }
        }
    }

    /** "Not given" has three spellings, and each is answered as a client that never heard of `include` is. */
    @Test
    fun `get_problem reads an absent, null or empty include as not asking for anything`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(aRecordRepository(root).containing(run).withRunCode(run, "a").query())
        val plain = invoker.call("get_problem", arguments("lessonId" to 120804))

        invoker.call("get_problem", includeRaw(JsonNull)) shouldBe plain
        invoker.call("get_problem", includeRaw(buildJsonArray { })) shouldBe plain
    }

    /** The code store throws if it is asked: an answer coming back shows the default path never reads kept code. */
    @Test
    fun `get_problem without include never reads kept code`() {
        val invoker = invokerOverBrokenCodes()

        failed(invoker.call("get_problem", arguments("lessonId" to 120804))).shouldBeFalse()
        failed(invoker.call("get_problem", includeRaw(JsonNull))).shouldBeFalse()
        failed(invoker.call("get_problem", includeRaw(buildJsonArray { }))).shouldBeFalse()
    }

    /**
     * `problem()` already holds one problem's records and the coded timeline is built from those, so the
     * log is read once however much `include` asks to be assembled. Reading it again for the timeline
     * would be a second picture of a log that may be growing between the two reads.
     */
    @Test
    fun `get_problem reads the record log once, with include or without`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:01:00+09:00")
        val repository = aRecordRepository(root).containing(run, submit)
            .withRunCode(run, "a").withSubmitCode(submit, "b")

        listOf(
            arguments("lessonId" to 120804),
            includeArguments("code"),
            includeArguments("runs"),
            includeArguments("code", "runs"),
        ).forEach { call ->
            val log = CountingRecordStore(repository.store())

            McpToolInvoker(repository.query(records = log)).call("get_problem", call)

            withClue("$call") { log.reads shouldBe 1 }
        }
    }

    @Test
    fun `get_problem takes a bare include string as one value`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(aRecordRepository(root).containing(run).withRunCode(run, "a").query())

        val items = itemsOf(invoker.call("get_problem", arguments("lessonId" to 120804, "include" to "runs")))

        items.single()["code"]!!.jsonPrimitive.content shouldBe "a"
    }

    /** Lenient about the spelling a model happens to use, strict about what is asked for. */
    @Test
    fun `get_problem reads include whatever its case and surrounding space, and ignores a repeat`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(aRecordRepository(root).containing(run).withRunCode(run, "a").query())

        val tidy = invoker.call("get_problem", includeArguments("runs"))

        failed(tidy).shouldBeFalse()
        invoker.call("get_problem", includeArguments(" RUNS ", "runs", "Runs")) shouldBe tidy
    }

    @Test
    fun `get_problem refuses an include it does not offer`() {
        invokerOver().call("get_problem", includeArguments("returned")).shouldBeAnIncludeRefusal()
    }

    /** Strict about the values: one it does not offer refuses the call whole, not the other half of it. */
    @Test
    fun `get_problem refuses a list with a value it does not offer, and a blank one`() {
        listOf(arrayOf("code", "returned"), arrayOf(""), arrayOf("  "), arrayOf("code,runs")).forEach { values ->
            invokerOver().call("get_problem", includeArguments(*values)).shouldBeAnIncludeRefusal()
        }
    }

    /** The bare form is held to the same values as the list: leniency about the type is not about the value. */
    @Test
    fun `get_problem refuses a bare string it does not offer, and a blank one`() {
        listOf("returned", "", "  ", "code,runs").forEach { bare ->
            invokerOver().call("get_problem", includeRaw(JsonPrimitive(bare))).shouldBeAnIncludeRefusal()
        }
    }

    /** A number is not the text of its digits, and an object or a nested list is no value at all. */
    @Test
    fun `get_problem refuses an include that is not text or a list of text, under its name`() {
        val notText = listOf<JsonElement>(
            JsonPrimitive(5),
            JsonPrimitive(true),
            buildJsonObject { },
            buildJsonArray { add(5) },
            buildJsonArray { add(JsonNull) },
            buildJsonArray { add(buildJsonArray { add("code") }) },
        )

        notText.forEach { include ->
            invokerOver().call("get_problem", includeRaw(include)).shouldBeAnIncludeRefusal()
        }
    }

    /** What is refused is refused before the log is read, as for every tool's arguments. */
    @Test
    fun `get_problem never reads the log for an include it has refused`() {
        val repository = aRecordRepository(root).containing(aRun(at = "2026-10-07T10:00:00+09:00"))
        val log = CountingRecordStore(repository.store())
        val invoker = McpToolInvoker(repository.query(records = log))

        invoker.call("get_problem", includeArguments("returned")).shouldBeAnIncludeRefusal()
        invoker.call("get_problem", includeRaw(JsonPrimitive(5))).shouldBeAnIncludeRefusal()

        log.reads shouldBe 0
    }

    /** A lesson with nothing recorded is an empty history, with `include` as without it. */
    @Test
    fun `get_problem with include answers an unrecorded lesson with an empty history, not an error`() {
        val result = invokerOver(aSubmissionRecord(lessonId = 131528))
            .call("get_problem", includeArguments("code", "runs"))

        failed(result).shouldBeFalse()
        itemsOf(result).shouldBeEmpty()
        structured(result)["submissionCount"]!!.jsonPrimitive.int shouldBe 0
    }

    /**
     * Past validation an IllegalArgumentException can only be an invariant of ours breaking, so the
     * assembly leaves as a state fault, as `repair_steps`' does, and not as advice to correct arguments
     * that were fine. Both values go through the one assembly, so both are shown.
     */
    @Test
    fun `get_problem with include reports a broken invariant behind valid arguments as a fault of ours`() {
        listOf("code", "runs").forEach { asked ->
            val thrown = shouldThrow<IllegalStateException> {
                invokerOverBrokenCodes().call("get_problem", includeArguments(asked))
            }

            thrown.message.shouldContain(INVARIANT)
            thrown.cause.shouldBeInstanceOf<IllegalArgumentException>()
        }
    }

    // The refusal teaches: it names the argument and what it takes, so a model that guessed a value can
    // retry without a second look. Any refusal at all would not do — an argument the tool did not know
    // is answered `unknown argument(s): "include"; …`, which names it and none of the values it takes.
    private fun JsonObject.shouldBeAnIncludeRefusal() {
        failed(this).shouldBeTrue()
        message(this).shouldContain("include")
        message(this).shouldContain("code")
        message(this).shouldContain("runs")
    }

    private fun includeArguments(vararg values: String): JsonObject = buildJsonObject {
        put("lessonId", 120804)
        putJsonArray("include") { values.forEach { add(it) } }
    }

    private fun includeRaw(include: JsonElement): JsonObject = buildJsonObject {
        put("lessonId", 120804)
        put("include", include)
    }

    private fun itemsOf(result: JsonObject): List<JsonObject> =
        structured(result)["submissions"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.isSubmit(): Boolean = this["action"]!!.jsonPrimitive.content == "submit"

    // Two failed runs of one problem, so the query has a step to assemble and reads the problem's code.
    private fun invokerOverBrokenCodes(): McpToolInvoker = McpToolInvoker(
        aRecordRepository(root).containing(*failedRuns(2)).query(codes = aBrokenGradingCodes(INVARIANT)),
    )

    // Failed runs one minute apart, none with code: each neighbouring pair is a step, so n runs make n - 1.
    private fun failedRuns(count: Int): Array<SubmissionRecord> = Array(count) { index ->
        aRun(at = "2026-10-07T10:%02d:00+09:00".format(index))
    }

    private fun JsonObject.correctionId(): String = this["to"]!!.jsonObject["recordId"]!!.jsonPrimitive.content

    private fun passWith(lessonId: Long, times: List<String?>) = aSubmissionRecord(
        lessonId = lessonId,
        verdict = Verdict.PASS,
        testcases = times.mapIndexed { index, time ->
            aTestcaseResult(id = index + 1L, passed = true, runTime = time)
        },
    )

    private fun invokerOver(
        vararg records: com.brokenfinger.tracker.domain.SubmissionRecord,
        catalog: com.brokenfinger.tracker.application.ProblemCatalog = anEmptyCatalog(),
        clock: Clock = Clock.systemUTC(),
    ): McpToolInvoker = McpToolInvoker(aRecordRepository(root).containing(*records).query(catalog, clock))

    // A history with one hole: frames were captured for a grading that no record represents.
    private fun invokerOverAHole(vararg records: SubmissionRecord): McpToolInvoker {
        val raw = FileRawSessionLog.under(root, Clock.systemUTC(), aStateDirectory(root))
        raw.orphaned(120802, """{"message":{"action":"submit","type":"finish"}}""")
        return McpToolInvoker(aRecordRepository(root).containing(*records).query(raw = raw))
    }

    // One call per tool, each with the arguments it needs, so a table over every tool cannot leave one out.
    private fun everyToolCall(): List<Pair<String, JsonObject>> = listOf(
        "submissions" to JsonObject(emptyMap()),
        "review_queue" to JsonObject(emptyMap()),
        "slow_passes" to JsonObject(emptyMap()),
        "list_problems" to JsonObject(emptyMap()),
        "repair_steps" to JsonObject(emptyMap()),
        "get_problem" to arguments("lessonId" to 120804),
        "stats" to arguments("groupBy" to "verdict"),
    )

    // The text a client reads up to the end of the warning. Whatever follows it is payload, so a text that
    // begins with all of this has put the warning before the first byte of the answer.
    private fun warningOpening(result: JsonObject): String =
        """{"incompleteHistory":${structured(result)["incompleteHistory"]}"""

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

    private companion object {
        val PROGRESS_FIELDS = setOf("attempted", "passed", "passedFirstSubmit", "runsBeforePass")

        /** What each tool takes, as its schema lists it and as `docs/mcp.md` documents it. */
        val TAKEN = mapOf(
            "submissions" to "since, verdict",
            "get_problem" to "lessonId, include",
            "stats" to "groupBy",
            "list_problems" to "level, part, tag, status",
            "review_queue" to "limit",
            "slow_passes" to "thresholdMs",
            "repair_steps" to "since, language, part, lessonId, limit",
        )

        /** Every key `include` can add to an item; the default answer carries none of them. */
        val CODE_KEYS = listOf(
            "code",
            "codeFetchedAt",
            "codeLate",
            "diffFromPrevGrading",
            "diffTruncated",
            "noDiff",
            "fromCodeLate",
        )

        /** What an invariant of ours says when it breaks; nothing a caller sent. */
        const val INVARIANT = "a label is one problem's"
    }
}
