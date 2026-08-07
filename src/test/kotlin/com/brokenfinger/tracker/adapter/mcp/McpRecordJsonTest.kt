package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.application.ProblemHistory
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.support.fixtures.aSqlSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class McpRecordJsonTest {
    @Test
    fun `carries the whole record, testcases included`() {
        val json = McpRecordJson.full(aSubmissionRecord())

        json.shouldContainKey("testcases")
        json.shouldContainKey("diffFromPrev")
        json["lessonId"]!!.jsonPrimitive.int shouldBe 120804
        json["testcases"]!!.jsonArray.size shouldBe 1
    }

    /**
     * The rule the whole projection exists for: a field the SQL path never sends is **absent**,
     * not null and not zero. Writing a zero score would drag every average down silently —
     * the outcome the constitution ranks worst ([[concepts/assumption-vs-measurement]]).
     */
    @Test
    fun `a value that was never recorded is absent, not blanked`() {
        val json = McpRecordJson.full(aSqlSubmissionRecord())

        json.shouldNotContainKey("score")
        json.shouldNotContainKey("rating")
        json.shouldNotContainKey("errorText")
    }

    /** A default is something we know, not something we are missing, so it is written out. */
    @Test
    fun `keeps the defaults that are measurements in their own right`() {
        val json = McpRecordJson.full(aSqlSubmissionRecord())

        json.shouldContainKey("tags")
        json["tags"]!!.jsonArray.shouldContainExactly()
        json["codePending"]!!.jsonPrimitive.booleanOrNull!!.shouldBeFalse()
    }

    @Test
    fun `the history view drops only the three fields that run to kilobytes`() {
        val summary = McpRecordJson.summary(aSubmissionRecord())

        summary.keys.shouldNotContain("testcases")
        summary.keys.shouldNotContain("errorText")
        summary.keys.shouldNotContain("diffFromPrev")
        summary.shouldContainKey("verdict")
        summary.shouldContainKey("tcSummary")
    }

    @Test
    fun `the history view keeps everything else the full view has`() {
        // A record carrying all three heavy fields, so the difference is the drop and
        // not merely a value that happened to be absent.
        val record = aSubmissionRecord(errorText = "Main.java:3: error: ';' expected")

        (McpRecordJson.full(record).keys - McpRecordJson.summary(record).keys)
            .shouldBe(setOf("testcases", "errorText", "diffFromPrev"))
    }

    @Test
    fun `a problem reports its submissions in full and counts them`() {
        val history = ProblemHistory(120804, "two numbers", 0, "intro", 91, listOf("구현"), listOf(aSubmissionRecord()))

        val json = McpRecordJson.problem(history)

        json["lessonId"]!!.jsonPrimitive.int shouldBe 120804
        json["title"]!!.jsonPrimitive.content shouldBe "two numbers"
        json["submissionCount"]!!.jsonPrimitive.int shouldBe 1
        json["submissions"]!!.jsonArray.size shouldBe 1
    }

    /** A problem with no recorded title returns no title. Not "Unknown", not an empty string. */
    @Test
    fun `a problem with nothing recorded carries no metadata keys at all`() {
        val json = McpRecordJson.problem(ProblemHistory(120804, null, null, null, null, emptyList(), emptyList()))

        json.shouldNotContainKey("title")
        json.shouldNotContainKey("level")
        json.shouldNotContainKey("part")
        json.shouldNotContainKey("acceptanceRate")
        json["submissionCount"]!!.jsonPrimitive.int shouldBe 0
        json["submissions"]!!.jsonArray.size shouldBe 0
    }

    /**
     * The summary drops `errorText` for weight, which without this field would trim the
     * explanation off the exact record a user asks an AI about — "why is this UNKNOWN when
     * my screen said 100?" (#74).
     */
    @Test
    fun `a cached-result unknown keeps its reason in the summary`() {
        val cached = aSubmissionRecord(
            outcome = Outcome.UNKNOWN,
            verdict = null,
            errorText = "같은 코드로 채점한 결과가 있습니다.",
        )

        val summary = McpRecordJson.summary(cached)

        summary["unknownReason"]?.jsonPrimitive?.content shouldBe "cached result"
        summary.containsKey("errorText") shouldBe false
    }

    @Test
    fun `an unexplained unknown carries no reason field at all`() {
        val odd = aSubmissionRecord(outcome = Outcome.UNKNOWN, verdict = null, errorText = "서버 점검 중입니다.")

        McpRecordJson.summary(odd).containsKey("unknownReason") shouldBe false
    }
}
