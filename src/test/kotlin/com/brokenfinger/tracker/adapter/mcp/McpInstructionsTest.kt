package com.brokenfinger.tracker.adapter.mcp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The only thing the model reads before it calls anything (#286).
 *
 * `docs/mcp.md` warns a **human** about every way these records mislead; the model never opens
 * that file. These assertions pin the warnings that have to travel with the surface itself, and
 * the line they must not cross — a fact about a field is not a judgement about a person.
 */
class McpInstructionsTest {
    private val instructions = McpDispatcher.INSTRUCTIONS

    /** The text wraps for readability, so assertions read it the way a model does — as prose. */
    private val prose = instructions.replace(Regex("\\s+"), " ")

    @Test
    fun `it still says what the server is and what it refuses to do`() {
        prose shouldContain "none of them interprets, ranks or advises"
        prose shouldContain "absent rather than filled in"
    }

    @Test
    fun `it names every tool, so the model does not reach for the wrong one`() {
        McpToolCatalog.NAMES.forEach { instructions shouldContain it }
    }

    /**
     * The four readings that produce a confidently wrong answer. Each has already caused one:
     * a run counted as an attempt (#235, #237), wall clock read as effort, an absent field read
     * as a zero, and a conclusion drawn over a history with holes (#169).
     */
    @Test
    fun `it warns about the readings that have actually gone wrong`() {
        prose shouldContain "A run is not an attempt"
        instructions shouldContain "elapsedSec"
        instructions shouldContain "focusedSec"
        prose shouldContain "Absent is not zero"
        instructions shouldContain "incompleteHistory"
    }

    /** "Slow" with no cohort means slow against this learner's own other passes, and nothing more. */
    @Test
    fun `it states what the records cannot speak about at all`() {
        prose shouldContain "Nothing about other learners"
    }

    /**
     * Said to the model rather than about it: the judgement is being handed over, not withheld
     * ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]).
     */
    @Test
    fun `it hands the reader the judgement the server will not make`() {
        prose shouldContain "The server counts and names nothing"
        prose shouldContain "deciding that is the reader's job"
    }

    /**
     * The line. Guidance on how to read a field is a fact about the field; a sentence *about the
     * learner* would be the server interpreting through the back door.
     *
     * The banned shapes are assertions, not the words — "weakness" appears above precisely
     * because the text refuses to name one, and a blacklist of topic words would forbid saying so.
     */
    @Test
    fun `it never asserts anything about the learner`() {
        listOf("you are ", "you tend", "the learner is", "this learner is", "you struggle").forEach {
            prose.lowercase() shouldNotContain it
        }
    }

    /** A wall of text clients truncate teaches nothing. */
    @Test
    fun `it stays short enough for a client to show in full`() {
        (instructions.length < 3000) shouldBe true
    }
}
