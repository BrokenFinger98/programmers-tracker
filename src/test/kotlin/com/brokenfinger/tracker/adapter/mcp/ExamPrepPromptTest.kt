package com.brokenfinger.tracker.adapter.mcp

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The only text in which this server asks for interpretation. These pin what it asks for and the
 * readings it must carry, because the server instructions have no room left for them (#353).
 */
class ExamPrepPromptTest {
    private val text = ExamPrepPrompt.text(ExamPrepScope())
    private val tools = McpToolCatalog.definitions().map { it.jsonObject }

    @Test
    fun `it asks for the naming in the open and for the records behind it`() {
        text shouldContain "The tools count and name nothing. Here the naming is asked for"
        text shouldContain "Group the steps into recurring patterns"
        text shouldContain "Name each pattern by what its diffs show"
        text shouldContain "cite the record ids behind it"
        text shouldContain "say how many problems it spans"
        text shouldContain "A pattern seen once is not a pattern."
    }

    @Test
    fun `it walks the five steps it asks for`() {
        text shouldContain "1. Call stats(groupBy=part), then stats(groupBy=level)."
        text shouldContain "Note where passing took the most runs and submits"
        text shouldContain "2. Call repair_steps()."
        text shouldContain "the problems to re-solve"
        text shouldContain "list_problems(status=untouched, part=<a part its steps come from>)"
        text shouldContain "two or three short drills aimed at exactly that point"
        text shouldContain "5. Last, what the records could not support."
    }

    @Test
    fun `it carries the readings a pre-exam session gets wrong`() {
        text shouldContain "A step shows what changed, not what was wrong."
        text shouldContain "Where the diff alone cannot tell, say so"
        text shouldContain "A run is not an attempt"
        text shouldContain "Absent is not zero"
        text shouldContain "Run code is kept by tracker versions from 2026-10-07 on"
        text shouldContain "Submit code was always kept"
        text shouldContain "`codeLate: true`"
        text shouldContain "build no pattern on it alone"
        text shouldContain "`incompleteHistory`"
        text shouldContain "say the counts have holes"
    }

    @Test
    fun `it reads the truncation the tool reports instead of trusting the first page`() {
        text shouldContain "When an answer says `truncated`, call again with `limit` set to its `total`"
    }

    /** A renamed tool would leave the prompt sending the model to one that does not exist. */
    @Test
    fun `every tool it sends the model to exists`() {
        listOf("stats", "repair_steps", "list_problems").forEach { tool ->
            McpToolCatalog.NAMES shouldContain tool
            text shouldContain tool
        }
    }

    /** The text names these arguments and values; a tool that stopped taking one would be asked the impossible. */
    @Test
    fun `every argument and value it passes is one the tools accept`() {
        enumOf("stats", "groupBy") shouldContainAll listOf("part", "level")
        enumOf("list_problems", "status") shouldContain "untouched"
        propertiesOf("list_problems") shouldContainKey "part"
        propertiesOf("repair_steps").shouldContainKeys("since", "language", "part", "limit")
    }

    @Test
    fun `the scope it is given narrows the repair_steps call it asks for`() {
        val scoped = ExamPrepPrompt.text(ExamPrepScope(language = "java", since = "2026-09-01"))

        scoped shouldContain "Scope: language \"java\", since \"2026-09-01\"."
        scoped shouldContain "2. Call repair_steps(language=\"java\", since=\"2026-09-01\")."
        scoped shouldNotContain "Scope: everything on record."
    }

    private fun propertiesOf(tool: String): JsonObject = schemaOf(tool).getValue("properties").jsonObject

    private fun schemaOf(tool: String): JsonObject =
        tools.single { it.getValue("name").jsonPrimitive.content == tool }.getValue("inputSchema").jsonObject

    private fun enumOf(tool: String, argument: String): List<String> =
        propertiesOf(tool).getValue(argument).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
}
