package com.brokenfinger.tracker.adapter.mcp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
}
