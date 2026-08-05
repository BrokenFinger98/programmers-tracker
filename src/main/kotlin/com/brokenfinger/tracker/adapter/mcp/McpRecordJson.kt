package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.application.ProblemHistory
import com.brokenfinger.tracker.domain.SubmissionRecord
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

    fun full(record: SubmissionRecord): JsonObject = format.encodeToJsonElement(record).jsonObject

    /**
     * The history view. Drops only the three fields that each run to kilobytes — a full
     * testcase list, a compiler dump, a diff — because `submissions` may answer with the
     * whole log and `get_problem` is where the detail lives.
     */
    fun summary(record: SubmissionRecord): JsonObject = JsonObject(full(record) - HEAVY)

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

    private val HEAVY = setOf("testcases", "errorText", "diffFromPrev")
}
