package com.brokenfinger.tracker.domain.calc

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks, two in-memory snapshots in and counts out (dev rules §3).
 *
 * The case that matters most is the third one: a tag with no records at all still gets a row.
 * That row is the isolated node in Obsidian's graph, and it is the whole reason the map exists
 * (#229) — a view built only from your own records cannot show a type you never met.
 */
class TagCoverageTest {
    private fun problem(id: Long, vararg tags: String) = CatalogSummary(
        lessonId = id,
        title = "p$id",
        level = 0,
        part = null,
        acceptanceRate = null,
        tags = tags.toList(),
    )

    @Test
    fun `a problem with two tags counts under both`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp", "math")),
            passed = setOf(1L),
            submitted = setOf(1L),
        )

        counts shouldContainExactly listOf(
            TagCount(tag = "dp", catalogTotal = 1, attempted = 1, solved = 1, touched = listOf(aTouch(1))),
            TagCount(tag = "math", catalogTotal = 1, attempted = 1, solved = 1, touched = listOf(aTouch(1))),
        )
    }

    @Test
    fun `a submit without a pass is attempted and not solved`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp")),
            passed = emptySet(),
            submitted = setOf(1L),
        )

        counts.single() shouldBe TagCount(
            tag = "dp",
            catalogTotal = 2,
            attempted = 1,
            solved = 0,
            touched = listOf(TouchedProblem(lessonId = 1, title = "p1", passed = false)),
        )
    }

    /** The graph's hole. Without this row an untouched type is absent rather than isolated. */
    @Test
    fun `a tag with no records at all still gets a row`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp"), problem(3, "tsp")),
            passed = emptySet(),
            submitted = emptySet(),
        )

        counts shouldContainExactly listOf(
            TagCount(tag = "dp", catalogTotal = 2, attempted = 0, solved = 0),
            TagCount(tag = "tsp", catalogTotal = 1, attempted = 0, solved = 0),
        )
    }

    @Test
    fun `a catalogued problem carrying no tag contributes nowhere`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1), problem(2, "dp")),
            passed = setOf(1L, 2L),
            submitted = setOf(1L, 2L),
        )

        counts.single() shouldBe TagCount(
            tag = "dp",
            catalogTotal = 1,
            attempted = 1,
            solved = 1,
            touched = listOf(TouchedProblem(lessonId = 2, title = "p2", passed = true)),
        )
    }

    /**
     * A record for a lesson the catalog does not describe cannot raise any tag's count, because
     * the count is taken over catalogued problems. Stated as a test because the opposite —
     * inventing a row from a record — is how a snapshot we do not own starts growing entries.
     */
    @Test
    fun `a submitted lesson outside the catalog raises nothing`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp")),
            passed = setOf(999L),
            submitted = setOf(999L),
        )

        counts.single() shouldBe TagCount(tag = "dp", catalogTotal = 1, attempted = 0, solved = 0)
    }

    /**
     * Alphabetical, and that is a boundary rather than a convenience: ordering the rows by how
     * badly a tag is going would be the judgement the server is forbidden to make. It also keeps
     * a rewrite of an unchanged map byte-identical.
     */
    @Test
    fun `rows come back in a stable order so a rewrite produces the same bytes`() {
        val catalogued = listOf(problem(1, "math"), problem(2, "dp"), problem(3, "arithmetic"))

        val counts = TagCoverage.of(catalogued, passed = emptySet(), submitted = emptySet())

        counts.map { it.tag } shouldContainExactly listOf("arithmetic", "dp", "math")
    }

    /**
     * The problems behind the counts, named (#241). Without them a tag note states two numbers
     * and cannot say which two problems they came from — the links exist only in Obsidian's
     * backlinks pane, which is not where someone clicking a tag node is looking.
     *
     * Naming a record is aggregation, not interpretation: these are the problems whose records
     * exist, in the order the catalog lists them, with no ranking of any kind.
     */
    @Test
    fun `a tag names the problems whose records raised its counts`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp"), problem(3, "dp")),
            passed = setOf(1L),
            submitted = setOf(1L, 2L),
        )

        counts.single().touched shouldContainExactly listOf(
            TouchedProblem(lessonId = 1, title = "p1", passed = true),
            TouchedProblem(lessonId = 2, title = "p2", passed = false),
        )
    }

    /** A tag nothing has been submitted to names nothing — that row is a count and an invitation. */
    @Test
    fun `an untouched tag names no problems`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "tsp")),
            passed = emptySet(),
            submitted = emptySet(),
        )

        counts.single().touched shouldContainExactly emptyList()
    }

    /**
     * Node size in Obsidian is the number of links a note has, so a tag must not link to another
     * tag: 27 catalog neighbours against 2 solved problems made `implementation` the biggest node
     * on a map of someone who had solved two problems (#241). Only the problems count now.
     */
    @Test
    fun `tags sharing a problem are not joined to each other`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp", "math")),
            passed = emptySet(),
            submitted = emptySet(),
        )

        counts.map { it.tag } shouldContainExactly listOf("dp", "math")
        counts.forEach { it.touched shouldContainExactly emptyList() }
    }

    /**
     * The same three words `list_problems` uses for a problem, derived the same way (#250). It is
     * `attempted` and `solved` restated, not a judgement about either: an Obsidian colour group
     * takes a search query rather than an expression, so "tried and not passed" needed a
     * two-clause negation until the state had a name.
     */
    @Test
    fun `a tag says where you stand, in the words a problem already uses`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "passed"), problem(2, "tried"), problem(3, "never")),
            passed = setOf(1L),
            submitted = setOf(1L, 2L),
        )

        counts.single { it.tag == "passed" }.status() shouldBe ProblemStatus.PASSED
        counts.single { it.tag == "tried" }.status() shouldBe ProblemStatus.ATTEMPTED
        counts.single { it.tag == "never" }.status() shouldBe ProblemStatus.UNTOUCHED
    }

    /** One pass among many attempts is still a pass — the tag has been cleared at least once. */
    @Test
    fun `a tag with a pass and an unpassed attempt is passed`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp")),
            passed = setOf(1L),
            submitted = setOf(1L, 2L),
        )

        counts.single().status() shouldBe ProblemStatus.PASSED
    }

    private fun aTouch(id: Long) = TouchedProblem(lessonId = id, title = "p$id", passed = true)
}
