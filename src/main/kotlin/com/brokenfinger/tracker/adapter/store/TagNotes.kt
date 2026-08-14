package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.domain.calc.TouchedProblem
import java.nio.file.Files

/**
 * The vault's tag map — `tags/<tag>.md`, one per catalogued tag (#229, spec
 * `2026-08-12-tag-map-vault-design`).
 *
 * It exists because Obsidian's graph draws links, and a graph built from your own records can
 * only show what you touched: a type never met is absent rather than faint. A note per
 * catalogued tag gives the graph a node to leave isolated, and the isolation is the finding.
 *
 * **Overwritten whole, every time**, exactly as [ProblemReadme] is. Human prose belongs in
 * `notes.md`, which the server never touches, and nothing here is merged with what the file
 * already held. Output depends only on the counts it is given, so rewriting an unchanged map
 * produces identical bytes and leaves the record repository's git history clean.
 *
 * The note states three counts and stops — no ratio, no ranking, no advice. That boundary is
 * the whole reason this exists rather than the `_weakness.md` design §5.5 once listed
 * ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]).
 */
class TagNotes(private val layout: RecordLayout) {
    fun write(counts: List<TagCount>) = counts.forEach { writeOne(it) }

    private fun writeOne(count: TagCount) {
        val file = layout.tagNote(count.tag)
        Files.createDirectories(file.parent)
        Files.writeString(file, render(count))
    }

    private fun render(count: TagCount): String =
        """
        |---
        |tag: ${count.tag}
        |catalogTotal: ${count.catalogTotal}
        |attempted: ${count.attempted}
        |solved: ${count.solved}
        |status: ${count.status().wireName()}
        |---
        |
        |# ${count.tag}
        |
        |Met ${count.attempted} of ${count.catalogTotal}, passed ${count.solved}.
        |${problemLines(count)}
        """.trimMargin()

    /**
     * The problems the counts came from, named and linked (#241).
     *
     * Split into passed and not, because those are the two things the numbers above already
     * distinguish and a single list would throw the distinction away. Neither line is a
     * judgement — both are lists of records that exist. A tag with nothing submitted renders
     * neither, rather than an empty label.
     *
     * **Tags no longer link to tags.** Obsidian sizes a node by how many notes link to it, so
     * the 27 catalog neighbours `implementation` carries made it the biggest node on the map of
     * someone who had solved two problems (#241, reversing #231). With only these links, size is
     * the number of problems you have worked on under that tag — which is what the map is for.
     */
    private fun problemLines(count: TagCount): String {
        val passed = linkLine("Passed", count.touched.filter { it.passed })
        val open = linkLine("Attempted without a pass", count.touched.filterNot { it.passed })
        if (passed.isEmpty() && open.isEmpty()) return ""
        return "\n" + passed + open
    }

    private fun linkLine(label: String, problems: List<TouchedProblem>): String {
        if (problems.isEmpty()) return ""
        return "$label: " + problems.joinToString(" ") { linkTo(it) } + "\n"
    }

    // The alias is the title as recorded; `[`, `]` and `|` would end the link early, so they go.
    private fun linkTo(problem: TouchedProblem): String =
        "[${aliasOf(problem.title)}](${layout.problemNoteLinkFromTag(problem.lessonId, problem.title)})"

    private fun aliasOf(title: String): String = title.filterNot { it in "[]|" }.trim().ifEmpty { "untitled" }
}
