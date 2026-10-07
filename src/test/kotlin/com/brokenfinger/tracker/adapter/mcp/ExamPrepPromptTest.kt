package com.brokenfinger.tracker.adapter.mcp

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The only text in which this server asks for interpretation. These pin what it asks for and the
 * readings it must carry, because the server instructions have no room left for them (#353).
 */
class ExamPrepPromptTest {
    private val text = ExamPrepPrompt.text(ExamPrepScope())

    @Test
    fun `it asks for the naming in the open and for the records behind it`() {
        text shouldContain "The tools count and name nothing. Here the naming is asked for"
        text shouldContain "cite the record ids behind it"
        text shouldContain "say how many problems it spans"
        text shouldContain "A pattern seen once is not a pattern."
    }

    @Test
    fun `it walks the five steps of the spec`() {
        text shouldContain "1. Call stats with groupBy=part, then with groupBy=level."
        text shouldContain "2. Call repair_steps()."
        text shouldContain "list_problems(status=untouched, part=<that pattern's part>)"
        text shouldContain "two or three short drills aimed at exactly that point"
        text shouldContain "5. Last, what the records could not support."
    }

    @Test
    fun `it carries the readings a pre-exam session gets wrong`() {
        text shouldContain "A step shows what changed, not what was wrong."
        text shouldContain "A run is not an attempt"
        text shouldContain "Absent is not zero"
        text shouldContain "Run code is kept by tracker versions from 2026-10-07 on"
        text shouldContain "`codeLate: true`"
        text shouldContain "`incompleteHistory`"
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

    @Test
    fun `the scope it is given narrows the repair_steps call it asks for`() {
        val scoped = ExamPrepPrompt.text(ExamPrepScope(language = "java", since = "2026-09-01"))

        scoped shouldContain "Scope: language \"java\", since \"2026-09-01\"."
        scoped shouldContain "2. Call repair_steps(language=\"java\", since=\"2026-09-01\")."
        scoped shouldNotContain "Scope: everything on record."
    }
}
