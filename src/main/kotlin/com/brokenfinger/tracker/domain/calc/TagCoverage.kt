package com.brokenfinger.tracker.domain.calc

/**
 * How much of each catalogued tag has been met — a pure calculator over two in-memory snapshots
 * (dev rules §3), taking the same inputs and the same [CatalogSummary] as [CatalogBrowse].
 *
 * **It counts and names nothing.** No ratio carries a verdict, no row is flagged, and the order
 * is alphabetical rather than most-neglected-first. An ordering by neglect would be the
 * judgement the server is forbidden to make
 * ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]); what counts as a gap belongs
 * to whoever reads the map.
 *
 * The denominator is the point. The shipped catalog uses 83 tags across 689 problems, and 37 of
 * them carry two problems or fewer while `implementation` carries 379 — so an untouched `tsp`
 * (one problem in the whole catalog) and an untouched `dp` (38) are not the same finding, and
 * without [TagCount.catalogTotal] they would be indistinguishable.
 *
 * `attempted` counts **submits**, matching `list_problems`' own `attempted` status. Counting
 * runs as well is arguably closer to *met the type*, and it was rejected: two surfaces answering
 * the same question with different numbers is the confusion #214 had to disclose its way out of.
 */
object TagCoverage {
    /**
     * @param catalogued every problem the snapshot describes
     * @param passed lessons with at least one passing submit
     * @param submitted lessons with at least one submit
     */
    fun of(catalogued: List<CatalogSummary>, passed: Set<Long>, submitted: Set<Long>): List<TagCount> = catalogued
        .flatMap { problem -> problem.tags.map { it to problem.lessonId } }
        .groupBy({ it.first }, { it.second })
        .map { (tag, lessons) -> countOf(tag, lessons, passed, submitted) }
        .sortedBy { it.tag }

    private fun countOf(tag: String, lessons: List<Long>, passed: Set<Long>, submitted: Set<Long>) = TagCount(
        tag = tag,
        catalogTotal = lessons.size,
        attempted = lessons.count { it in submitted },
        solved = lessons.count { it in passed },
    )
}

/**
 * One tag's standing. Every field is a count, and there is deliberately no ratio and no flag: a
 * stored ratio invites a stored threshold, and a threshold is a verdict rather than a
 * measurement — the line design §5.5's own example `slowFlag` crossed.
 */
data class TagCount(val tag: String, val catalogTotal: Int, val attempted: Int, val solved: Int)
