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
 *
 * **Tags are not joined to each other, and that is a reversal** (#241, superseding the #231
 * amendment). Obsidian sizes a graph node by how many notes link to it, so 27 catalog neighbours
 * against 2 solved problems made `implementation` the largest node on the map of someone who had
 * solved two problems — the map showed the catalog's shape, not the reader's. "Never met" is
 * carried by an Obsidian colour group on `["solved":0]`, which says it without touching size.
 */
object TagCoverage {
    /**
     * @param catalogued every problem the snapshot describes
     * @param passed lessons with at least one passing submit
     * @param submitted lessons with at least one submit
     */
    fun of(catalogued: List<CatalogSummary>, passed: Set<Long>, submitted: Set<Long>): List<TagCount> = catalogued
        .flatMap { problem -> problem.tags.map { it to problem } }
        .groupBy({ it.first }, { it.second })
        .map { (tag, problems) -> countOf(tag, problems, passed, submitted) }
        .sortedBy { it.tag }

    private fun countOf(tag: String, problems: List<CatalogSummary>, passed: Set<Long>, submitted: Set<Long>) =
        TagCount(
            tag = tag,
            catalogTotal = problems.size,
            attempted = problems.count { it.lessonId in submitted },
            solved = problems.count { it.lessonId in passed },
            touched = problems.filter { it.lessonId in submitted }.map { touchOf(it, passed) },
        )

    private fun touchOf(problem: CatalogSummary, passed: Set<Long>) = TouchedProblem(
        lessonId = problem.lessonId,
        title = problem.title,
        passed = problem.lessonId in passed,
    )
}

/**
 * One tag's standing. Every field is a count, and there is deliberately no ratio and no flag: a
 * stored ratio invites a stored threshold, and a threshold is a verdict rather than a
 * measurement — the line design §5.5's own example `slowFlag` crossed.
 */
data class TagCount(
    val tag: String,
    val catalogTotal: Int,
    val attempted: Int,
    val solved: Int,
    /**
     * The problems behind [attempted] and [solved], in the catalog's own order.
     *
     * They are named so the tag note can link them (#241). A count with no way back to the
     * records it came from sends the reader to Obsidian's backlinks pane, which is not where
     * someone clicking a tag is looking.
     */
    val touched: List<TouchedProblem> = emptyList(),
) {
    /**
     * Where you stand with this tag, in the three words [ProblemStatus] already gives a problem.
     *
     * Derived, never stored: it is [attempted] and [solved] restated, and a second field could
     * disagree with the two it summarises. **It names a state, not a weakness** — nothing here
     * says `attempted` is bad, which is the boundary
     * [[decisions/2026-08-12-the-server-counts-and-names-nothing]] draws.
     *
     * It exists because an Obsidian graph colour group takes a *search query*, not an expression:
     * "tried and not passed" was `-["attempted":0] ["solved":0]`, correct and nobody's idea of a
     * setting. With a word it is `["status":"attempted"]` (#250).
     */
    fun status(): ProblemStatus = when {
        solved > 0 -> ProblemStatus.PASSED
        attempted > 0 -> ProblemStatus.ATTEMPTED
        else -> ProblemStatus.UNTOUCHED
    }
}

/** One problem a tag's counts came from. A record that exists, and nothing said about it. */
data class TouchedProblem(val lessonId: Long, val title: String, val passed: Boolean)
