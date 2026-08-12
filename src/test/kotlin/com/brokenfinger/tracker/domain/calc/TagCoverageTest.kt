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
            TagCount(tag = "dp", catalogTotal = 1, attempted = 1, solved = 1, related = listOf("math")),
            TagCount(tag = "math", catalogTotal = 1, attempted = 1, solved = 1, related = listOf("dp")),
        )
    }

    @Test
    fun `a submit without a pass is attempted and not solved`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp")),
            passed = emptySet(),
            submitted = setOf(1L),
        )

        counts.single() shouldBe TagCount(tag = "dp", catalogTotal = 2, attempted = 1, solved = 0)
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

        counts.single() shouldBe TagCount(tag = "dp", catalogTotal = 1, attempted = 1, solved = 1)
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
     * The map needed edges *between* tags, not only into them. With links only from problems, a
     * live vault showed 81 of 83 tags isolated — and "an isolated node is a gap" carries
     * information only when isolation is rare (#231).
     *
     * Co-occurrence is a count of what solved.ac already tagged, so it decides nothing: two
     * techniques are related when a problem carries both.
     */
    @Test
    fun `tags carried by the same problem are related to each other`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp", "math"), problem(2, "math", "sorting")),
            passed = emptySet(),
            submitted = emptySet(),
        )

        counts.single { it.tag == "dp" }.related shouldContainExactly listOf("math")
        counts.single { it.tag == "math" }.related shouldContainExactly listOf("dp", "sorting")
    }

    /** Alphabetical, not by how many problems they share — an ordering is a claim of its own. */
    @Test
    fun `the related tags come back alphabetically`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "zebra", "dp", "math")),
            passed = emptySet(),
            submitted = emptySet(),
        )

        counts.single { it.tag == "dp" }.related shouldContainExactly listOf("math", "zebra")
    }

    @Test
    fun `a tag that never shares a problem is related to nothing, and is never related to itself`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp")),
            passed = emptySet(),
            submitted = emptySet(),
        )

        counts.single().related shouldContainExactly emptyList()
    }
}
