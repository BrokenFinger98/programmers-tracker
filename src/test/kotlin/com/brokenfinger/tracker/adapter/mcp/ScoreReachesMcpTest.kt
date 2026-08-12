package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.RatingChange
import com.brokenfinger.tracker.domain.Score
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * #193 wired the score and the rating into the record; this pins that they reach a client, and
 * that they still vanish when absent.
 *
 * Worth its own test because nothing named them: `full()` encodes the whole record, so they
 * arrived by inheritance rather than by decision — and a field that arrives by inheritance
 * leaves the same way, silently, the next time the serializer's shape changes.
 */
class ScoreReachesMcpTest {
    @Test
    fun `a summary carries the score and the rating`() {
        val record = aSubmissionRecord(
            score = Score(user = "100.0", perfect = "100.0"),
            rating = RatingChange.of(old = 1371, new = 1372),
        )

        val summary = McpRecordJson.summary(record)

        summary["score"]!!.jsonObject["user"]!!.jsonPrimitive.content shouldBe "100.0"
        summary["rating"]!!.jsonObject["new"]!!.jsonPrimitive.content shouldBe "1372"
        summary["rating"]!!.jsonObject["changed"]!!.jsonPrimitive.content shouldBe "true"
    }

    /** Missing data looks missing (docs/mcp.md): a SQL grading has no rating, and says nothing. */
    @Test
    fun `a grading with neither carries neither key`() {
        val summary = McpRecordJson.summary(aSubmissionRecord(score = null, rating = null))

        summary.shouldNotContainKey("score")
        summary.shouldNotContainKey("rating")
    }
}
