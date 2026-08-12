package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.TagCount
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
        |---
        |
        |# ${count.tag}
        |
        |Met ${count.attempted} of ${count.catalogTotal}, passed ${count.solved}.
        |${relatedLine(count)}
        """.trimMargin()

    /**
     * The edges between tags, and the reason the map has any shape at all before problems are
     * solved: with links only from problems, a live vault showed 81 of 83 tags isolated, and
     * isolation carries information only when it is rare (#231).
     *
     * Two techniques are joined when a catalogued problem carries both — a count of what
     * solved.ac already tagged, so no threshold and no ordering by strength. A tag that shares
     * nothing renders no line rather than an empty label.
     */
    private fun relatedLine(count: TagCount): String {
        if (count.related.isEmpty()) return ""
        return "\nShares problems with: " + count.related.joinToString(" ") { "[[${layout.tagNoteLink(it)}]]" } + "\n"
    }
}
