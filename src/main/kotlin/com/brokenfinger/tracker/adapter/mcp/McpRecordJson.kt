package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.application.ProblemHistory
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.BrowsedProblem
import com.brokenfinger.tracker.domain.calc.ReviewItem
import com.brokenfinger.tracker.domain.calc.SlowPass
import com.brokenfinger.tracker.domain.calc.UnknownReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * One problem in full — every record it has, and **two counts that split them.**
     *
     * `submissionCount` used to be the array's length, so `get_problem` answered 15 where
     * `list_problems` called the same problem 8 attempts (#237): the array holds runs too, and a
     * run is not an attempt (design §5.1). The words are the vault's — `problems/<id>/README.md`
     * has carried `attempts` and `runCount` side by side since it was written, and two views of
     * one problem should not need two vocabularies.
     *
     * The array keeps every record, because `get_problem` is where the compiler output lives and
     * that only comes from the run path.
     */
    fun problem(history: ProblemHistory): JsonObject = buildJsonObject {
        put("lessonId", history.lessonId)
        history.title?.let { put("title", it) }
        history.level?.let { put("level", it) }
        history.part?.let { put("part", it) }
        history.acceptanceRate?.let { put("acceptanceRate", it) }
        put("tags", format.encodeToJsonElement(history.tags))
        put("submissionCount", history.submissions.count { it.isSubmission() })
        put("runCount", history.submissions.count { !it.isSubmission() })
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
        // Half the identity, not decoration: one problem appears once per language it was
        // passed in, and a reader seeing the same lessonId twice needs this to tell them apart.
        put("language", item.language)
        item.level?.let { put("level", it) }
        put("passedAt", item.passedAt.toString())
        put("attempts", item.attempts)
        item.sawQuestions?.let { put("sawQuestions", it) }
        item.focusedSec?.let { put("focusedSec", it) }
        put("confidence", item.confidence.wireName())
        put("dueAt", item.dueAt.toString())
        put("overdueDays", item.overdueDays)
    }

    /** Milliseconds as a number, not a string: the record keeps the judge's own spelling, but
     * a caller comparing speeds should not have to parse it back. */
    fun slowPasses(slow: List<SlowPass>): JsonArray = JsonArray(slow.map(::slowPass))

    private fun slowPass(pass: SlowPass): JsonObject = buildJsonObject {
        put("lessonId", pass.lessonId)
        put("title", pass.title)
        pass.level?.let { put("level", it) }
        put("tags", JsonArray(pass.tags.map(::JsonPrimitive)))
        put("language", pass.language)
        put("passedAt", pass.passedAt.toString())
        put("slowestMs", pass.slowestMs)
        put("slowestCaseId", pass.slowestCaseId)
        put("timedCases", pass.timedCases)
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
