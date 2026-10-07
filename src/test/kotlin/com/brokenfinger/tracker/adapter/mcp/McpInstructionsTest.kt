package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.TallyGroup
import com.brokenfinger.tracker.support.fixtures.MCP_TEXT_BUDGET
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeInRange
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
     * "Is not withheld for lack of ability" read two ways, and one of them is "not hidden". The hand-over
     * says what the server does not do — no weakness, no next step — and that the reason is not
     * incapacity, in words that have one reading.
     */
    @Test
    fun `it says plainly that the server names no weakness, and that this is not for lack of ability`() {
        prose shouldContain "names nothing — no weakness, no next step — and not for lack of ability"
        prose shouldNotContain "withheld"
    }

    /** The quotes mark `untouched` as the status name `list_problems` answers with, not as a word for a gap. */
    @Test
    fun `it quotes untouched as the status name where it says what a newer problem is`() {
        prose shouldContain "a newer problem is missing, not \"untouched\""
    }

    /** "By date or verdict" alone reads as grouped by them; the tool narrows the whole log. */
    @Test
    fun `it says submissions narrows the whole log`() {
        prose shouldContain "the whole log, narrowed by date or verdict"
    }

    /**
     * An overrun is cut from the tail, so what must not be lost comes before what can be: the hand-over sits
     * ahead of the lists, not behind them, and a cut would reach the lists first.
     */
    @Test
    fun `it puts the hand-over ahead of the lists, where a cut reaches last`() {
        val handOver = instructions.indexOf("The server counts and names nothing")

        handOver shouldBeInRange (0 until instructions.indexOf("WHICH TOOL ANSWERS WHAT"))
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

    /** Spec 2026-10-07 §4.3: a diff is evidence of a change, and two fields say when not to trust it. */
    @Test
    fun `it says what a repair step is, and the fields that qualify its diff`() {
        prose shouldContain "what changed, not what was wrong"
        instructions shouldContain "noDiff"
        instructions shouldContain "codeLate"
    }

    /** What the tracker keeps is a fact about its versions; a guessed date, or "exists", reads as a fact about the problem. */
    @Test
    fun `it dates run code by the tracker versions that kept it, and by no other date`() {
        prose shouldContain "Run code is kept by tracker versions from 2026-10-07 on"
        prose shouldNotContain "2026-08-07"
    }

    /** The schema enumerates the groupings; the instructions are what the model reads first, so they list the same. */
    @Test
    fun `it names every grouping stats offers`() {
        val groups = TallyGroup.wireNames()

        prose shouldContain "counts per ${groups.dropLast(1).joinToString(", ")} or ${groups.last()}"
    }

    /**
     * The client cuts server instructions at 2,048 characters and keeps the head (Claude Code CHANGELOG 2.1.84,
     * "capped at 2KB"; 2.1.280, `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` "to change the 2,048-character cap on MCP
     * tool descriptions and server instructions"). The budget is [MCP_TEXT_BUDGET], 48 under the cap: a text
     * past it is cut where nobody chose, and the last thing in this one is what the reader most needs not to lose.
     */
    @Test
    fun `it fits the budget under the client's cap, so none of it is cut`() {
        withClue("the instructions are ${instructions.length} characters") {
            instructions.length shouldBeLessThanOrEqualTo MCP_TEXT_BUDGET
        }
    }
}
