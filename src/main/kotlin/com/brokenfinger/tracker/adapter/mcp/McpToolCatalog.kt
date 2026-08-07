package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.Since
import com.brokenfinger.tracker.domain.calc.TallyGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The tools this server exposes — three, and deliberately not the twenty of design §7.
 *
 * The rest of §7 is absent rather than stubbed: a tool that answered "not implemented"
 * would be worse than an absent one, because a client discovers it through `tools/list`
 * and plans around it. Exam state and a review schedule do not exist yet. The catalog and
 * the tag vocabulary now do — they ship in the jar — so `list_problems` is merely
 * unexposed (#100), not unsupported.
 *
 * Every description says what the tool *counts*, never what it concludes. Interpretation
 * belongs to the AI reading the numbers (CLAUDE.md, design §7).
 *
 * The order is fixed rather than derived from a map, because the specification asks for a
 * deterministic `tools/list` so clients can cache it.
 */
object McpToolCatalog {
    const val SUBMISSIONS = "submissions"
    const val GET_PROBLEM = "get_problem"
    const val STATS = "stats"

    val NAMES = listOf(SUBMISSIONS, GET_PROBLEM, STATS)

    fun definitions(): JsonArray = buildJsonArray {
        add(submissions())
        add(getProblem())
        add(stats())
    }

    private fun submissions(): JsonObject = tool(
        name = SUBMISSIONS,
        title = "Submission history",
        description = "Every recorded run and submit, newest first, optionally narrowed by date or verdict. " +
            "Returns the stored records themselves; per-testcase detail, compiler output and diffs are " +
            "omitted here and available from get_problem. A field that was never recorded is absent.",
    ) {
        putJsonObject("properties") {
            putJsonObject("since") {
                put("type", "string")
                put("description", Since.FORMAT + ". A bare date is read in the record's own UTC offset.")
            }
            putJsonObject("verdict") {
                put("type", "string")
                put("description", "Keep only submissions the judge resolved to this verdict.")
                putJsonArray("enum") { Verdict.entries.forEach { add(it.name) } }
            }
        }
    }

    private fun getProblem(): JsonObject = tool(
        name = GET_PROBLEM,
        title = "One problem and its attempts",
        description = "Everything recorded against one Programmers lesson: catalog metadata as captured, and " +
            "every submission in full, including per-testcase results and compiler output. A lesson with " +
            "nothing recorded answers with an empty history rather than an error — we report what we " +
            "observed, which may be nothing.",
    ) {
        putJsonObject("properties") {
            putJsonObject("lessonId") {
                put("type", "integer")
                put("description", "The Programmers lesson id, as it appears in the problem page URL.")
            }
        }
        putJsonArray("required") { add("lessonId") }
    }

    private fun stats(): JsonObject = tool(
        name = STATS,
        title = "Counts by group",
        description = "Counts submissions per bucket. Counts only — it ranks nothing and concludes nothing. " +
            "An entry with no `key` counts the submissions whose grouping value was never recorded, which " +
            "is not the same as a bucket named unknown.",
    ) {
        putJsonObject("properties") {
            putJsonObject("groupBy") {
                put("type", "string")
                put("description", "What to count by.")
                putJsonArray("enum") { TallyGroup.wireNames().forEach { add(it) } }
            }
        }
        putJsonArray("required") { add("groupBy") }
    }

    // `additionalProperties: false` on every schema: an argument we do not understand is a
    // client bug or a stale tool list, and silently ignoring it would answer a question
    // narrower than the one that was asked.
    private fun tool(name: String, title: String, description: String, schema: JsonObjectBuilderScope): JsonObject =
        buildJsonObject {
            put("name", name)
            put("title", title)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                schema()
                put("additionalProperties", false)
            }
        }
}

private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
