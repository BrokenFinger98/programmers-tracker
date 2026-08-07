package com.brokenfinger.tracker.domain.calc

/**
 * How far a learner has got with one catalogued problem — the join `list_problems` exists
 * to expose (#100).
 *
 * `UNTOUCHED` is the answer no per-lesson lookup can give: the records know only what was
 * attempted, so "never tried" and "tried and failed" are indistinguishable from them alone.
 * Separating those two is the whole point of shipping a catalog (design §5.6).
 */
enum class ProblemStatus {
    UNTOUCHED,
    ATTEMPTED,
    PASSED,
    ;

    fun wireName(): String = name.lowercase()

    companion object {
        fun from(raw: String): ProblemStatus = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "status must be one of ${entries.joinToString { it.wireName() }}, was \"$raw\"",
            )
    }
}

/** One catalogued problem with the learner's standing against it. */
data class BrowsedProblem(
    val lessonId: Long,
    val title: String,
    val level: Int?,
    val part: String?,
    val acceptanceRate: Int?,
    val tags: List<String>,
    val status: ProblemStatus,
    /** Submits only; a run is not an attempt at the answer. Zero for an untouched problem. */
    val attempts: Int,
)

/**
 * Joins the shipped catalog against what was recorded — a pure calculator over two
 * in-memory snapshots (dev rules §3), so the browse and any later re-analysis cannot drift.
 *
 * The filters are all narrowing and all optional. A filter naming something the catalog
 * does not contain returns nothing rather than everything: an empty answer to a precise
 * question is honest, where a full one silently answers a different question.
 */
object CatalogBrowse {
    /**
     * @param catalogued every problem the snapshot describes
     * @param passedIds lessons with at least one passing submit
     * @param attemptsById submits per lesson; absent means never submitted
     */
    fun of(
        catalogued: List<CatalogSummary>,
        passedIds: Set<Long>,
        attemptsById: Map<Long, Int>,
        level: Int? = null,
        part: String? = null,
        tag: String? = null,
        status: ProblemStatus? = null,
    ): List<BrowsedProblem> = catalogued
        .asSequence()
        .filter { level == null || it.level == level }
        .filter { part == null || it.part.equals(part, ignoreCase = true) }
        .filter { tag == null || it.tags.any { each -> each.equals(tag, ignoreCase = true) } }
        .map { it.withStanding(passedIds, attemptsById) }
        .filter { status == null || it.status == status }
        .toList()

    private fun CatalogSummary.withStanding(passed: Set<Long>, attempts: Map<Long, Int>): BrowsedProblem {
        val submits = attempts[lessonId] ?: 0
        return BrowsedProblem(
            lessonId = lessonId,
            title = title,
            level = level,
            part = part,
            acceptanceRate = acceptanceRate,
            tags = tags,
            status = when {
                lessonId in passed -> ProblemStatus.PASSED
                submits > 0 -> ProblemStatus.ATTEMPTED
                else -> ProblemStatus.UNTOUCHED
            },
            attempts = submits,
        )
    }
}

/**
 * A catalogued problem as this calculator needs it — deliberately not `CatalogEntry`, which
 * lives in `application`. `domain` imports nothing (dev rules §1), and the browse is
 * arithmetic over fields rather than knowledge of where a catalog comes from.
 */
data class CatalogSummary(
    val lessonId: Long,
    val title: String,
    val level: Int?,
    val part: String?,
    val acceptanceRate: Int?,
    val tags: List<String>,
)
