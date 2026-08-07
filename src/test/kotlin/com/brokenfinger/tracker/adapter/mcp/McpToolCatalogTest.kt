package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.TallyGroup
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
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
     * Three, and the absence of the rest is the point. Design §7 lists about twenty; the
     * others need a catalog, a tag vocabulary, exam state or a review schedule, none of
     * which exist. A tool that answered "not implemented" would be worse than an absent
     * one, because a client discovers it through `tools/list` and plans around it.
     */
    @Test
    fun `exposes exactly the four tools that can be answered from what ships`() {
        tools.map { it["name"]!!.jsonPrimitive.content }
            .shouldContainExactly("submissions", "get_problem", "stats", "list_problems")
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
}
