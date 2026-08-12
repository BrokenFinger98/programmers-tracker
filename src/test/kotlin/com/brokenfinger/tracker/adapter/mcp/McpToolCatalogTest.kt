package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.TallyGroup
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class McpToolCatalogTest {
    private val tools = McpToolCatalog.definitions().map { it.jsonObject }

    /**
     * Six, and the absence of the rest is the point. Design §7 lists about twenty; the
     * others need exam state, a company profile or a write path that does not exist. A tool
     * that answered "not implemented" would be worse than an absent one, because a client
     * discovers it through `tools/list` and plans around it.
     */
    @Test
    fun `exposes exactly the six tools that can be answered from what ships`() {
        tools.map { it["name"]!!.jsonPrimitive.content }
            .shouldContainExactly(
                "submissions",
                "get_problem",
                "stats",
                "list_problems",
                "review_queue",
                "slow_passes",
            )
    }

    /** The spec asks for a deterministic order so clients can cache the list. */
    @Test
    fun `lists the tools in the same order every time`() {
        repeat(5) {
            McpToolCatalog.definitions().map { tool -> tool.jsonObject["name"]!!.jsonPrimitive.content }
                .shouldContainExactly(McpToolCatalog.NAMES)
        }
    }

    @Test
    fun `every tool is fully described`() {
        tools.forEach { tool ->
            tool["name"]!!.jsonPrimitive.content.shouldNotBeBlank()
            tool["title"]!!.jsonPrimitive.content.shouldNotBeBlank()
            tool["description"]!!.jsonPrimitive.content.shouldNotBeBlank()
            tool.shouldContainKey("inputSchema")
        }
    }

    @Test
    fun `every schema is a closed object, so an argument we do not understand is refused`() {
        tools.forEach { tool ->
            val schema = tool["inputSchema"]!!.jsonObject

            schema["type"]!!.jsonPrimitive.content shouldBe "object"
            schema["additionalProperties"]!!.jsonPrimitive.booleanOrNull!!.shouldBeFalse()
        }
    }

    /** Drift guard: the schema enumerates the domain, so a new verdict cannot go unlisted. */
    @Test
    fun `the verdict filter enumerates every measured verdict`() {
        enumOf("submissions", "verdict").shouldContainExactly(Verdict.entries.map { it.name })
    }

    @Test
    fun `the stats grouping enumerates every group the calculator supports`() {
        enumOf("stats", "groupBy").shouldContainExactly(TallyGroup.wireNames())
    }

    @Test
    fun `requires exactly the arguments that have no sensible default`() {
        required("submissions").shouldContainExactly()
        required("get_problem").shouldContainExactly("lessonId")
        required("stats").shouldContainExactly("groupBy")
    }

    @Test
    fun `takes the lesson id as a number`() {
        property("get_problem", "lessonId")["type"]!!.jsonPrimitive.content shouldBe "integer"
    }

    @Test
    fun `offers the history filters as optional`() {
        properties("submissions").keys.shouldContainExactly(setOf("since", "verdict"))
    }

    private fun tool(name: String): JsonObject = tools.single { it["name"]!!.jsonPrimitive.content == name }

    private fun properties(name: String): JsonObject = tool(name)["inputSchema"]!!.jsonObject["properties"]!!.jsonObject

    private fun property(tool: String, field: String): JsonObject = properties(tool)[field]!!.jsonObject

    private fun enumOf(tool: String, field: String): List<String> =
        property(tool, field)["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun required(name: String): List<String> =
        tool(name)["inputSchema"]!!.jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    /**
     * Found by reading all six descriptions as a client, after a `curl` check that printed the
     * last 180 characters of one of them missed it (#203).
     *
     * The mechanism will recur: #187 appended a shared sentence to every description and left the
     * bespoke one `stats` already had, so it said the same thing twice. The next tool to earn a
     * note of its own is the next chance to do it again.
     */
    @Test
    fun `no description says the incomplete-history sentence twice`() {
        val phrase = "gradings were captured that no record represents"

        tools.forEach { tool ->
            val description = tool["description"]!!.jsonPrimitive.content
            val occurrences = description.split(phrase).size - 1

            withClue(tool["name"]!!.jsonPrimitive.content) { occurrences shouldBe 1 }
        }
    }

    /**
     * The two surfaces group differently and both are right: `review_queue` and `slow_passes` key
     * on (problem, language) since #174, and `stats(groupBy=problem)` collapses the languages
     * because a submission count per problem is the question it answers. A reader who does not
     * know that sees one problem as seven queue items and one bucket, and reads it as a
     * disagreement — the clean-slate sweep produced exactly that shape (#214).
     *
     * Pinned on `stats` rather than left to prose because the description is the only place a
     * client learns it: `tools/list` is read once, and the results are counts.
     */
    @Test
    fun `stats says that grouping by problem collapses the languages`() {
        val description = tool(McpToolCatalog.STATS)["description"]!!.jsonPrimitive.content

        description shouldContain "counts across languages"
        description shouldContain McpToolCatalog.REVIEW_QUEUE
        description shouldContain McpToolCatalog.SLOW_PASSES
    }

    /**
     * The tools that hand back whole records must explain `elapsedSec`, because the name reads as
     * time on task and the value is wall clock — a measured record carries 77251 beside a
     * `focusedSec` of 37 (#205). The ones that return counts or a schedule never show the field,
     * and repeating it there would be weight for nothing.
     */
    @Test
    fun `only the tools that return records explain elapsedSec`() {
        val explains = tools.filter { it["description"]!!.jsonPrimitive.content.contains("elapsedSec") }
            .map { it["name"]!!.jsonPrimitive.content }

        explains shouldContainExactly listOf(McpToolCatalog.SUBMISSIONS, McpToolCatalog.GET_PROBLEM)
    }
}
