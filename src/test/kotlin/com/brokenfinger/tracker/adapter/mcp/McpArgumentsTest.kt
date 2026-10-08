package com.brokenfinger.tracker.adapter.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class McpArgumentsTest {
    /** A key is the client's text: quoted, "a, b" stays one item and a newline cannot break the message (#365). */
    @Test
    fun `each unknown key is quoted as a JSON string, so a comma or a newline stays inside one item`() {
        val message = McpArguments.unknown("stats", setOf("a, b", "a\nb"), listOf("groupBy"))

        message shouldBe "unknown argument(s): \"a\\nb\", \"a, b\"; stats takes groupBy"
        message shouldNotContain "\n"
    }

    /** Sorted, so the same call is refused in the same words whatever order its keys came in. */
    @Test
    fun `several unknown keys come back sorted`() {
        McpArguments.unknown("stats", setOf("b", "a"), listOf("groupBy")) shouldBe
            "unknown argument(s): \"a\", \"b\"; stats takes groupBy"
    }

    /** In the order given — the order the listing documents them in — and never sorted. */
    @Test
    fun `what the owner takes is named in the order given`() {
        val taken = listOf("since", "language", "part", "lessonId", "limit")

        McpArguments.unknown("repair_steps", setOf("verdict"), taken) shouldBe
            "unknown argument(s): \"verdict\"; repair_steps takes since, language, part, lessonId, limit"
    }

    /** No tool takes none today; one that did must not be answered "takes" and nothing after it. */
    @Test
    fun `an owner that takes no arguments says so`() {
        McpArguments.unknown("ping", setOf("x"), emptyList()) shouldBe
            "unknown argument(s): \"x\"; ping takes no arguments"
    }

    /** Absent and JSON null both say "not given", and neither is read as the text "null". */
    @Test
    fun `a text argument that is absent or null is not given`() {
        val arguments = buildJsonObject { put("language", JsonNull) }

        McpArguments.optionalText(arguments, "language").shouldBeNull()
        McpArguments.optionalText(arguments, "part").shouldBeNull()
    }

    /** As it came: what a blank or padded value means is each path's to decide, not the reader's. */
    @Test
    fun `a text argument is read as it came, blank and padding included`() {
        listOf("java", "", "  ", " GROUP BY ").forEach { given ->
            McpArguments.optionalText(buildJsonObject { put("part", given) }, "part") shouldBe given
        }
    }

    /** A number is not the text of its digits: `language: 5` is a mistake to say so, under the argument's name. */
    @Test
    fun `a text argument that is not a JSON string is refused under its name`() {
        listOf(JsonPrimitive(5), JsonPrimitive(true), JsonArray(listOf(JsonPrimitive("java"))), JsonObject(emptyMap()))
            .forEach { notText ->
                withClue("language = $notText") {
                    shouldThrow<IllegalArgumentException> {
                        McpArguments.optionalText(buildJsonObject { put("language", notText) }, "language")
                    }.message shouldBe "language must be text"
                }
            }
    }
}
