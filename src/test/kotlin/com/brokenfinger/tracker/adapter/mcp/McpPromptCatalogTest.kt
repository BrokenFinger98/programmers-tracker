package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class McpPromptCatalogTest {
    @Test
    fun `it lists one prompt, exam_prep`() {
        McpPromptCatalog.definitions().map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .shouldContainExactly(ExamPrepPrompt.NAME)
    }

    /** Claude Code fills arguments by position, split on spaces — the order is an interface (D2). */
    @Test
    fun `its arguments come in the order Claude Code fills them, none required`() {
        val arguments = McpPromptCatalog.definitions().single().jsonObject["arguments"]!!.jsonArray

        val names = arguments.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        names.shouldContainExactly("language", "since", "part")
        arguments.forEach { it.jsonObject["required"]!!.jsonPrimitive.boolean shouldBe false }
    }

    @Test
    fun `get answers one user message carrying the text`() {
        val answer = McpPromptCatalog.get(ExamPrepPrompt.NAME, JsonObject(emptyMap()))

        val message = answer["messages"]!!.jsonArray.single().jsonObject
        message["role"]!!.jsonPrimitive.content shouldBe "user"
        message["content"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "text"
        message["content"]!!.jsonObject["text"]!!.jsonPrimitive.content shouldBe ExamPrepPrompt.text(ExamPrepScope())
    }

    @Test
    fun `the arguments given reach the text`() {
        val arguments = buildJsonObject {
            put("language", "java")
            put("since", "2026-09-01")
        }

        val text = textOf(McpPromptCatalog.get(ExamPrepPrompt.NAME, arguments))

        text shouldContain "repair_steps(language=\"java\", since=\"2026-09-01\")"
    }

    /** "String, Date" is a real part name: through the catalog too, a part is one value in the call. */
    @Test
    fun `a part reaches the call as one quoted value`() {
        textWithPart("GROUP") shouldContain "repair_steps(part=\"GROUP\")"
        textWithPart("String, Date") shouldContain "repair_steps(part=\"String, Date\")"
    }

    /** Read as given, then trimmed — the tools' own parser trims too, so the call names the date itself. */
    @Test
    fun `a since padded with spaces is read as the date it holds`() {
        val text = textOf(McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("since", " 2026-09-01 ") }))

        text shouldContain "since=\"2026-09-01\""
    }

    /** A form-style client sends an empty field as "" — that is "not given", not a value. */
    @Test
    fun `a blank or null argument is not given`() {
        val arguments = buildJsonObject {
            put("language", "  ")
            put("since", JsonNull)
        }

        textOf(McpPromptCatalog.get(ExamPrepPrompt.NAME, arguments)) shouldContain "Scope: everything on record."
    }

    @Test
    fun `an unknown prompt is refused as invalid params, naming the one there is`() {
        val refused = shouldThrow<McpFailure> { McpPromptCatalog.get("warmup_plan", JsonObject(emptyMap())) }

        refused.code shouldBe McpErrors.INVALID_PARAMS
        refused.status shouldBe 400
        refused.message shouldContain ExamPrepPrompt.NAME
    }

    @Test
    fun `a missing name is refused the same way`() {
        val refused = shouldThrow<McpFailure> { McpPromptCatalog.get(null, JsonObject(emptyMap())) }

        refused.code shouldBe McpErrors.INVALID_PARAMS
    }

    @Test
    fun `an unknown argument is refused by name`() {
        val refused = shouldThrow<McpFailure> {
            McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("level", "2") })
        }

        refused.code shouldBe McpErrors.INVALID_PARAMS
        refused.message shouldContain "level"
    }

    @Test
    fun `a since that does not parse is refused in the tools' words`() {
        val refused = shouldThrow<McpFailure> {
            McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("since", "yesterday") })
        }

        refused.code shouldBe McpErrors.INVALID_PARAMS
        refused.message shouldStartWith Since.FORMAT
        refused.message shouldContain "positional"
    }

    /**
     * Prompt arguments are strings by the specification, all three. A number is not the text of its digits,
     * and a value that is not a primitive at all must be refused, not thrown through as an internal error.
     */
    @Test
    fun `an argument that is not a JSON string is refused under its name, not answered as a fault`() {
        val notStrings = listOf<JsonElement>(
            JsonPrimitive(5),
            JsonPrimitive(true),
            JsonArray(listOf(JsonPrimitive("java"))),
            buildJsonObject { put("name", "java") },
        )

        listOf("language", "since", "part").forEach { name ->
            notStrings.forEach { notText -> assertRefusedAsText(name, notText) }
        }
    }

    private fun assertRefusedAsText(name: String, notText: JsonElement) {
        withClue("$name = $notText") {
            val refused = shouldThrow<McpFailure> {
                McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put(name, notText) })
            }

            refused.code shouldBe McpErrors.INVALID_PARAMS
            refused.message shouldBe "$name must be text"
        }
    }

    private fun textWithPart(part: String): String =
        textOf(McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("part", part) }))

    private fun textOf(answer: JsonObject): String =
        answer["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content
}
