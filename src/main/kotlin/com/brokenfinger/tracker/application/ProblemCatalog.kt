package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.LessonId

/**
 * One problem as the shipped catalog describes it — reference data, no behaviour.
 *
 * It lives beside the port rather than beside the adapter that happens to read it today.
 * `application` must not import `adapter` (dev rules §1), and a second source for the same
 * facts — a locally fetched page, say — would otherwise have to depend on the classpath
 * reader to describe a problem.
 */
data class CatalogEntry(
    val id: Long,
    val title: String,
    val level: Int?,
    val partTitle: String?,
    val acceptanceRate: Int?,
    val tags: List<String>,
)

/**
 * What is known about a problem independently of anything the user did to it — its title,
 * its level, and the techniques it needs.
 *
 * **Every lookup may miss, and a miss is not a failure.** The catalog is a snapshot of a
 * catalogue we do not own, so a problem published after it was built is simply absent. A
 * caller that treats absence as an error would refuse to record a grading that is perfectly
 * valid, which inverts what matters: the grading cannot be re-fetched, the title can.
 */
interface ProblemCatalog {
    fun find(lessonId: LessonId): CatalogEntry?

    /** The techniques this problem needs, from the adopted vocabulary. Empty when unknown. */
    fun tagsOf(lessonId: LessonId): List<String>

    /** The problem's title, or null — never a placeholder that reads like a real one. */
    fun titleOf(lessonId: LessonId): String?
}
