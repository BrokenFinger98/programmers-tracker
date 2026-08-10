package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.application.RecordQuery
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.ProblemStatus
import com.brokenfinger.tracker.domain.calc.Since
import com.brokenfinger.tracker.domain.calc.TallyBucket
import com.brokenfinger.tracker.domain.calc.TallyGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Runs one `tools/call`.
 *
 * The two failure kinds are kept apart on purpose, because MCP treats them differently.
 * An argument a model can fix — a date it spelled wrong, a `groupBy` that is not one of
 * the three — comes back as a **tool execution error** (`isError: true`) carrying the
 * correction, which the client is expected to hand to the model. An unknown tool is a
 * **protocol error**, because no rewording of the arguments will make it exist.
 *
 * Nothing here writes. The record repository is opened read-only through [RecordQuery], so
 * a prompt-injected instruction to "delete my failures" has no path to act on.
 */
class McpToolInvoker(private val query: RecordQuery) {
    fun call(name: String?, arguments: JsonObject): JsonObject = when (name) {
        McpToolCatalog.SUBMISSIONS -> executed { submissions(checked(arguments, SUBMISSION_ARGS)) }
        McpToolCatalog.GET_PROBLEM -> executed { problem(checked(arguments, PROBLEM_ARGS)) }
        McpToolCatalog.STATS -> executed { stats(checked(arguments, STATS_ARGS)) }
        McpToolCatalog.LIST_PROBLEMS -> executed { listProblems(checked(arguments, LIST_ARGS)) }
        McpToolCatalog.REVIEW_QUEUE -> executed { reviewQueue(checked(arguments, REVIEW_ARGS)) }
        McpToolCatalog.SLOW_PASSES -> executed { slowPasses(checked(arguments, SLOW_ARGS)) }
        else -> throw McpFailure(
            McpErrors.INVALID_PARAMS,
            400,
            "unknown tool; this server exposes ${McpToolCatalog.NAMES.joinToString()}",
        )
    }

    private fun submissions(arguments: JsonObject): JsonObject {
        val found = query.submissions(
            since = arguments.text("since")?.let(Since::from),
            verdict = arguments.text("verdict")?.let(::verdictOf),
        )
        return buildJsonObject {
            put("count", found.size)
            put("submissions", McpRecordJson.summaries(found))
        }
    }

    private fun problem(arguments: JsonObject): JsonObject = McpRecordJson.problem(query.problem(lessonIdOf(arguments)))

    private fun listProblems(arguments: JsonObject): JsonObject {
        val found = query.browse(
            level = arguments.wholeNumber("level"),
            part = arguments.text("part"),
            tag = arguments.text("tag"),
            status = arguments.text("status")?.let(ProblemStatus::from),
        )
        return buildJsonObject {
            put("count", found.size)
            put("problems", McpRecordJson.problems(found))
        }
    }

    private fun reviewQueue(arguments: JsonObject): JsonObject {
        val due = query.reviewQueue(limitOf(arguments))
        return buildJsonObject {
            put("count", due.size)
            put("due", McpRecordJson.reviewItems(due))
        }
    }

    // Zero would ask for an empty answer and mean nothing; a negative cannot be honoured at
    // all. Both are refused rather than rounded up into a question nobody asked (dev rules §4).
    private fun limitOf(arguments: JsonObject): Int? {
        val limit = arguments.wholeNumber("limit") ?: return null
        require(limit > 0) { "limit must be a positive whole number" }
        return limit
    }

    private fun slowPasses(arguments: JsonObject): JsonObject {
        val report = query.slowPasses(thresholdOf(arguments))
        return buildJsonObject {
            put("count", report.slow.size)
            put("untimed", report.untimed)
            put("slow", McpRecordJson.slowPasses(report.slow))
        }
    }

    // A time cannot be negative and zero would keep everything, which is what absent means
    // already. Both are refused rather than reinterpreted (dev rules §4).
    private fun thresholdOf(arguments: JsonObject): Double? {
        val raw = arguments["thresholdMs"] ?: return null
        val ms = (raw as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: throw IllegalArgumentException("thresholdMs must be a number of milliseconds")
        require(ms > 0) { "thresholdMs must be greater than zero" }
        return ms
    }

    private fun stats(arguments: JsonObject): JsonObject {
        val raw = arguments.text("groupBy") ?: throw IllegalArgumentException("groupBy is required")
        val group = TallyGroup.from(raw)
        val buckets = query.tally(group)
        return buildJsonObject {
            put("groupBy", group.wireName())
            put("total", buckets.sumOf { it.count })
            put("entries", JsonArray(buckets.map(::bucketOf)))
        }
    }

    // An absent key is omitted rather than written as null — it means the grouping value
    // was never recorded, and `"key": null` would read like a bucket that has one.
    private fun bucketOf(bucket: TallyBucket): JsonObject = buildJsonObject {
        bucket.key?.let { put("key", it) }
        bucket.label?.let { put("label", it) }
        put("count", bucket.count)
    }

    // Lenient about the JSON type, strict about the value: models quote numbers routinely,
    // and refusing "120804" would fail a call that is not actually wrong.
    private fun lessonIdOf(arguments: JsonObject): Long {
        val raw = arguments["lessonId"] ?: throw IllegalArgumentException("lessonId is required")
        val id = (raw as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("lessonId must be a whole number")
        require(id > 0) { "lessonId must be positive" }
        return id
    }

    private fun verdictOf(raw: String): Verdict =
        Verdict.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("verdict must be one of ${Verdict.entries.joinToString()}")

    // The schemas say `additionalProperties: false`, so the server enforces it rather than
    // answering a narrower question than the one it was asked.
    private fun checked(arguments: JsonObject, allowed: Set<String>): JsonObject {
        val unknown = arguments.keys - allowed
        require(unknown.isEmpty()) { "unknown argument(s): ${unknown.sorted().joinToString()}" }
        return arguments
    }

    // Only IllegalArgumentException becomes a tool error. Anything else is a fault of ours,
    // and dressing it as advice to the model would send it correcting arguments that were fine.
    private fun executed(payload: () -> JsonObject): JsonObject = try {
        succeeded(payload())
    } catch (invalid: IllegalArgumentException) {
        failed(invalid.message ?: "the arguments could not be used")
    }

    // The JSON goes back twice on purpose: `structuredContent` for the client, and the same
    // text for clients on revisions that predate it (spec, Tools — Structured Content).
    private fun succeeded(payload: JsonObject): JsonObject = buildJsonObject {
        putJsonArray("content") { add(textOf(payload.toString())) }
        put("structuredContent", payload)
        put("isError", false)
    }

    private fun failed(message: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(textOf(message)) }
        put("isError", true)
    }

    private fun textOf(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    // Absent means "do not narrow"; present but not a number is the client's mistake and is
    // said so, rather than quietly widening the question that was asked.
    private fun JsonObject.wholeNumber(name: String): Int? {
        val raw = this[name] ?: return null
        return (raw as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: throw IllegalArgumentException("$name must be a whole number")
    }

    private companion object {
        val SUBMISSION_ARGS = setOf("since", "verdict")
        val PROBLEM_ARGS = setOf("lessonId")
        val STATS_ARGS = setOf("groupBy")
        val LIST_ARGS = setOf("level", "part", "tag", "status")
        val REVIEW_ARGS = setOf("limit")
        val SLOW_ARGS = setOf("thresholdMs")
    }
}
