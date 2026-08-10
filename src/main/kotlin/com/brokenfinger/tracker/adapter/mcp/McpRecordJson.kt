package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.application.ProblemHistory
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.BrowsedProblem
import com.brokenfinger.tracker.domain.calc.ReviewItem
import com.brokenfinger.tracker.domain.calc.UnknownReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * How a stored record reaches an AI.
 *
 * **A field that was never recorded is absent, not blank and not zero.** That is the whole
 * point of `explicitNulls = false`: a problem whose title we never captured has no `title`
 * key, so a reader cannot mistake a gap in our observation for a measurement we made
 * ([[concepts/assumption-vs-measurement]]). Defaults *are* encoded, because `tags: []` and
 * `codePending: false` are things we know rather than things we are missing.
 *
 * The projection is derived from the stored record rather than re-declared, so a field
 * added to design §5.2 appears here without a second edit — and cannot silently go missing.
 */
object McpRecordJson {
    private val format = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    fun full(record: SubmissionRecord): JsonObject = withReason(record, format.encodeToJsonElement(record).jsonObject)

    /**
     * The history view. Drops only the three fields that each run to kilobytes — a full
     * testcase list, a compiler dump, a diff — because `submissions` may answer with the
     * whole log and `get_problem` is where the detail lives.
     */
    fun summary(record: SubmissionRecord): JsonObject = JsonObject(full(record) - HEAVY)

    /**
     * The classified reason an UNKNOWN is unknown (#74). Carried as its own short field
     * because the summary view drops `errorText` for weight — without this, the one record a
     * user will ask an AI about ("why is this UNKNOWN when my screen said 100?") is the one
     * whose explanation was trimmed. Absent when unmeasured, per the classifier.
     */
    private fun withReason(record: SubmissionRecord, encoded: JsonObject): JsonObject {
        val reason = UnknownReason.of(record.outcome, record.errorText) ?: return encoded
        return JsonObject(encoded + ("unknownReason" to format.encodeToJsonElement(reason.label)))
    }

    fun summaries(records: List<SubmissionRecord>): JsonArray = JsonArray(records.map(::summary))

    fun problem(history: ProblemHistory): JsonObject = buildJsonObject {
        put("lessonId", history.lessonId)
        history.title?.let { put("title", it) }
        history.level?.let { put("level", it) }
        history.part?.let { put("part", it) }
        history.acceptanceRate?.let { put("acceptanceRate", it) }
        put("tags", format.encodeToJsonElement(history.tags))
        put("submissionCount", history.submissions.size)
        put("submissions", JsonArray(history.submissions.map(::full)))
    }

    /**
     * The catalog browse (#100). A field the snapshot does not carry is **left out**, the
     * same rule the record serializers follow: an absent level and a level of zero mean
     * different things, and only one of them was measured.
     */
    /**
     * The schedule and every fact behind it. Absent stays absent: a sensor field is omitted
     * rather than written as null, because `"sawQuestions": null` reads like an observation
     * and "we were not watching" is not one (#132).
     */
    fun reviewItems(due: List<ReviewItem>): JsonArray = JsonArray(due.map(::reviewItem))

    private fun reviewItem(item: ReviewItem): JsonObject = buildJsonObject {
        put("lessonId", item.lessonId)
        put("title", item.title)
        item.level?.let { put("level", it) }
        put("passedAt", item.passedAt.toString())
        put("attempts", item.attempts)
        item.sawQuestions?.let { put("sawQuestions", it) }
        item.focusedSec?.let { put("focusedSec", it) }
        put("confidence", item.confidence.wireName())
        put("dueAt", item.dueAt.toString())
        put("overdueDays", item.overdueDays)
    }

    fun problems(found: List<BrowsedProblem>): JsonArray = JsonArray(
        found.map { problem ->
            buildJsonObject {
                put("lessonId", problem.lessonId)
                put("title", problem.title)
                problem.level?.let { put("level", it) }
                problem.part?.let { put("part", it) }
                problem.acceptanceRate?.let { put("acceptanceRate", it) }
                put("tags", format.encodeToJsonElement(problem.tags))
                put("status", problem.status.wireName())
                put("attempts", problem.attempts)
            }
        },
    )

    private val HEAVY = setOf("testcases", "errorText", "diffFromPrev")
}
