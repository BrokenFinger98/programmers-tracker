package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.SubmissionRecord
import java.nio.file.Path

/**
 * `problems/README.md` — what has been solved, in the one form a browser can read (#292).
 *
 * The record repository is the product, and opening it on github.com showed nothing:
 * `dashboard.base` renders as raw YAML because it is an Obsidian query and Obsidian is not there,
 * the root `README.md` was a usage guide, and `problems/` is a folder listing. Everything the
 * repository knows was in it and none of it was legible where a browser opens.
 *
 * A **directory README**, because GitHub renders one at the root of any directory — and browsing
 * into `problems/` is exactly where someone clicks to ask the question. It also introduces no new
 * ownership category: this file is regenerated from the records like every problem page and tag
 * note beside it, rather than being a fourth kind of file with its own rules.
 *
 * **Since #414 the front page carries the same table** ([RootIndex]), and this page stays as it was: the one place
 * the index always is, whoever the front page belongs to. Both render through [IndexTable], so they cannot disagree.
 *
 * **Counts, never a verdict.** How many problems and how many passed are facts; newest-first is an
 * ordering of when things happened, not a ranking of anything
 * ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]).
 */
class ProblemIndex(private val layout: RecordLayout) {
    private val writes = RecordWrites.underProblems(layout)
    private val table = IndexTable(layout::problemNoteLinkFromIndex)

    /**
     * Null when there is nothing recorded, and no file either.
     *
     * An index of an empty directory helps nobody, and writing one would break a property
     * `StartupReconciliation` states out loud: a boot that had nothing to recover does nothing.
     * Manufacturing a commit to say "nothing yet" is exactly the noise that claim exists to
     * prevent.
     *
     * Null too when it was not written, which [RecordWrites] has said: refused, for a link on the way (#361), or
     * failed, as a file held open on Windows does (#407). Derived from the log and written again at every attachment
     * and boot, so such an index is skipped rather than thrown.
     */
    fun write(records: List<SubmissionRecord>): Path? {
        if (records.isEmpty()) return null
        val file = layout.problemIndex()
        return file.takeIf { writes.replaceOrSkip(it, HEADING + table.render(records)) }
    }

    private companion object {
        const val HEADING = "# Problems\n"
    }
}

/**
 * The index's counts and its table, one row per problem — what `problems/README.md` and the front page both carry
 * (#292, #414). The two differ only in where a link starts, so each hands in how a problem's page is linked from it,
 * which [RecordLayout] answers: a problem Programmers renamed is linked at the directory it has (#233).
 */
internal class IndexTable(private val linkOf: (lessonId: Long, title: String?) -> String) {
    /** The counts, then the table — none when nothing is recorded, since a table with no rows reads as a defect. */
    fun render(records: List<SubmissionRecord>): String {
        val problems = byProblem(records)
        if (problems.isEmpty()) return summary(problems)
        return summary(problems) + table(problems)
    }

    /**
     * One row per problem, newest submission first — and a problem with only runs still counts,
     * because opening and running something is a fact about the history even when nothing was
     * ever submitted.
     */
    private fun byProblem(records: List<SubmissionRecord>): List<List<SubmissionRecord>> =
        records.groupBy { it.lessonId }.values.sortedByDescending { group -> group.maxOf { it.ts } }

    private fun summary(problems: List<List<SubmissionRecord>>): String {
        val passed = problems.count { group -> submits(group).any { it.verdict?.name == PASS } }
        return "\n${problems.size} problems recorded, $passed passed.\n"
    }

    private fun table(problems: List<List<SubmissionRecord>>): String =
        "\n$HEADER\n$SEPARATOR\n" + problems.joinToString("\n") { row(it) } + "\n"

    private fun row(group: List<SubmissionRecord>): String {
        val newest = group.maxBy { it.ts }
        val submits = submits(group)
        return "| ${link(newest)} | ${cell(newest.level?.toString())} | ${cell(newest.kind?.name?.lowercase())} " +
            "| ${cell(newest.language)} | ${cell(verdict(submits))} | ${submits.size} " +
            "| ${cell(submits.maxByOrNull { it.ts }?.ts?.toLocalDate()?.toString())} |"
    }

    /** The newest submit's verdict — a run has none, and a problem with only runs shows a dash. */
    private fun verdict(submits: List<SubmissionRecord>): String? = submits.maxByOrNull { it.ts }?.verdict?.name

    private fun submits(group: List<SubmissionRecord>): List<SubmissionRecord> = group.filter { it.isSubmission() }

    /**
     * A relative markdown link, resolved through the layout so a problem Programmers renamed is
     * linked at the directory it actually has (#233) — and readable in every renderer rather than
     * only in Obsidian (#293).
     *
     * The title is a non-null `String` whose **blank** spelling means unknown:
     * `SettledCapture.toRecord` writes `problem?.title.orEmpty()`, so a problem the cached catalog
     * has never seen arrives here as `""`. The lesson id is what the row links by then — the
     * directory is named for it either way, so the link still resolves; only its text changes.
     */
    private fun link(record: SubmissionRecord): String {
        val title = record.title.ifBlank { null } ?: record.lessonId.toString()
        return "[$title](${linkOf(record.lessonId, record.title)})"
    }

    /** Absent stays absent — an em dash rather than a blank cell, which reads as a table defect. */
    private fun cell(value: String?): String = value?.ifBlank { null } ?: "—"

    private companion object {
        const val PASS = "PASS"
        const val HEADER = "| Problem | Level | Kind | Language | Verdict | Submits | Last submit |"
        const val SEPARATOR = "|---|---|---|---|---|---|---|"
    }
}
