package com.brokenfinger.tracker.domain.calc

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The join `list_problems` rests on (#100), as a pure calculator over two snapshots
 * (dev rules §3): the shipped catalog, and what the records say was submitted.
 *
 * Its reason for existing is `UNTOUCHED`. The records alone cannot tell "never tried" from
 * "tried and failed" — both are simply absent from a verdict tally — and those two call for
 * entirely different prescriptions (design §5.6).
 */
class CatalogBrowseTest {
    @Test
    fun `a problem with no submits is untouched, which the records alone could never say`() {
        val browsed = CatalogBrowse.of(
            catalogued = listOf(aProblem(1), aProblem(2)),
            passedIds = setOf(1),
            attemptsById = mapOf(
                1L to 1,
            ),
        )

        browsed.map { it.lessonId to it.status } shouldContainExactly listOf(
            1L to ProblemStatus.PASSED,
            2L to ProblemStatus.UNTOUCHED,
        )
    }

    /** A submit that never passed is attempted, and its count is what makes it interesting. */
    @Test
    fun `submits without a pass make a problem attempted, carrying how many`() {
        val browsed = CatalogBrowse.of(listOf(aProblem(7)), passedIds = emptySet(), attemptsById = mapOf(7L to 4))

        browsed.single().status shouldBe ProblemStatus.ATTEMPTED
        browsed.single().attempts shouldBe 4
    }

    /** A pass stays a pass however many attempts it took — the count is kept, not folded in. */
    @Test
    fun `a pass after several attempts is passed, and still reports the attempts`() {
        val browsed = CatalogBrowse.of(listOf(aProblem(7)), passedIds = setOf(7), attemptsById = mapOf(7L to 5))

        browsed.single().status shouldBe ProblemStatus.PASSED
        browsed.single().attempts shouldBe 5
    }

    @Test
    fun `filters narrow, and combine`() {
        val catalogued = listOf(
            aProblem(1, level = 0, part = "코딩테스트 입문", tags = listOf("구현")),
            aProblem(2, level = 1, part = "코딩테스트 입문", tags = listOf("구현", "정렬")),
            aProblem(3, level = 1, part = "연습문제", tags = listOf("정렬")),
        )

        ids(CatalogBrowse.of(catalogued, emptySet(), emptyMap(), level = 1)) shouldContainExactly listOf(2L, 3L)
        ids(CatalogBrowse.of(catalogued, emptySet(), emptyMap(), tag = "정렬")) shouldContainExactly listOf(2L, 3L)
        ids(CatalogBrowse.of(catalogued, emptySet(), emptyMap(), level = 1, tag = "구현")) shouldContainExactly listOf(2L)
    }

    /** Case is the client's business, not the catalog's — a tag typed lower-case still matches. */
    @Test
    fun `part and tag match case-insensitively`() {
        val catalogued = listOf(aProblem(1, part = "Coding Test", tags = listOf("Implementation")))

        ids(CatalogBrowse.of(catalogued, emptySet(), emptyMap(), part = "coding test")) shouldContainExactly listOf(1L)
        ids(CatalogBrowse.of(catalogued, emptySet(), emptyMap(), tag = "IMPLEMENTATION")) shouldContainExactly
            listOf(1L)
    }

    /**
     * An empty answer to a precise question, rather than a full one to a different question:
     * a filter naming something the catalog does not contain must narrow to nothing.
     */
    @Test
    fun `a filter that matches nothing returns nothing, never everything`() {
        val catalogued = listOf(aProblem(1, level = 0), aProblem(2, level = 1))

        CatalogBrowse.of(catalogued, emptySet(), emptyMap(), level = 99).shouldBeEmpty()
        CatalogBrowse.of(catalogued, emptySet(), emptyMap(), tag = "no such tag").shouldBeEmpty()
    }

    /** The status filter runs after the join, because status is not a catalog fact. */
    @Test
    fun `the status filter selects on the joined standing`() {
        val catalogued = listOf(aProblem(1), aProblem(2), aProblem(3))

        val untouched = CatalogBrowse.of(
            catalogued,
            passedIds = setOf(1),
            attemptsById = mapOf(1L to 1, 2L to 2),
            status = ProblemStatus.UNTOUCHED,
        )

        ids(untouched) shouldContainExactly listOf(3L)
    }

    /** A record for a problem outside the snapshot cannot invent a catalog row for it. */
    @Test
    fun `a submitted problem the catalog does not contain is simply not listed`() {
        val browsed = CatalogBrowse.of(listOf(aProblem(1)), passedIds = setOf(999), attemptsById = mapOf(999L to 3))

        ids(browsed) shouldContainExactly listOf(1L)
    }

    private fun ids(browsed: List<BrowsedProblem>) = browsed.map { it.lessonId }

    private fun aProblem(id: Long, level: Int? = 0, part: String? = "코딩테스트 입문", tags: List<String> = listOf("구현")) =
        CatalogSummary(
            lessonId = id,
            title = "problem $id",
            level = level,
            part = part,
            acceptanceRate = 90,
            tags = tags,
        )
}
