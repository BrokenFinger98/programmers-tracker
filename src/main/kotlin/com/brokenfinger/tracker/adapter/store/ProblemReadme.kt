package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.UnknownReason
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter

/**
 * The `README.md` of one problem, derived from its records (design §5.5).
 *
 * **Overwritten whole, every time.** Human prose lives in `notes.md`, which the server never
 * touches; nothing here is ever merged with what the file already held. The design is explicit
 * that server-written and human-written text never share a file, because a marker-delimited
 * region eventually breaks and then the two are indistinguishable.
 *
 * The page states only what the records know. The catalog has been carried into records since
 * #69, so a record of a catalogued problem brings its title, level, part and acceptance rate;
 * a record that lacks them — a problem outside the shipped catalog — leaves those fields out
 * entirely and heads the page with the lesson id. An invented title would read exactly like a
 * real one, which is the silent-wrong-data outcome CLAUDE.md names as the worst possible.
 *
 * Output depends on the records alone, so regenerating an unchanged problem rewrites the same
 * bytes and leaves the record repository's git history clean.
 */
class ProblemReadme(private val layout: RecordLayout) {
    /** Writes the page for one problem's records, oldest first, and returns the file. */
    fun write(records: List<SubmissionRecord>): Path {
        require(records.isNotEmpty()) { "a README needs at least one record" }
        val lessonId = records.first().lessonId
        require(records.all { it.lessonId == lessonId }) { "records must all belong to lesson $lessonId" }
        val file = layout.problemDirectory(lessonId, titleOf(records)).resolve(README)
        Files.createDirectories(file.parent)
        Files.writeString(file, render(records))
        return file
    }

    private fun render(records: List<SubmissionRecord>): String =
        frontmatter(records) + heading(records) + history(records)

    private fun frontmatter(records: List<SubmissionRecord>): String {
        val fields = identity(records) + catalog(records) + progress(records)
        return "---\n${fields.joinToString("\n")}\n---\n"
    }

    private fun identity(records: List<SubmissionRecord>): List<String> = listOfNotNull(
        "lessonId: ${records.first().lessonId}",
        field("title", quoted(titleOf(records))),
        field("language", quoted(languageOf(records))),
    )

    /**
     * **The language that was submitted**, so it means the same thing as the `verdict` two lines
     * below it.
     *
     * It used to be the newest record of any kind, and lesson 120802 — passed in java, then run
     * once in python3 — published `language: "python3"` beside `verdict: PASS`, for a run that
     * produced no verdict at all (#248). Everything else on this page that varies per grading is
     * submit-only already; this was the one field counting runs, and the one a reader pairs with
     * the outcome.
     *
     * Absent when nothing has been submitted. Borrowing a run's language would name a language
     * nothing was ever judged in, and `runCount` already says the problem was touched.
     */
    private fun languageOf(records: List<SubmissionRecord>): String? =
        latest(submits(records)) { it.language.ifBlank { null } }

    // Omitted rather than defaulted when a record carries no catalog metadata: `level: 0`
    // would be indistinguishable from a genuine Lv0 problem.
    private fun catalog(records: List<SubmissionRecord>): List<String> = listOfNotNull(
        field("level", levelOf(records)?.toString()),
        field("part", quoted(latest(records) { it.part?.ifBlank { null } })),
        field("acceptanceRate", latest(records) { it.acceptanceRate }?.toString()),
        field("tags", tagsOf(records)?.joinToString(", ", "[", "]") { quoted(it).orEmpty() }),
    )

    private fun progress(records: List<SubmissionRecord>): List<String> = listOfNotNull(
        field("verdict", submits(records).lastOrNull { it.verdict != null }?.verdict?.name),
        "attempts: ${submits(records).maxOfOrNull { it.attempt } ?: 0}",
        "runCount: ${records.count { it.action == GradingAction.RUN }}",
        "elapsedSec: ${records.last().elapsedSec}",
        "firstSeen: ${records.first().ts.toLocalDate()}",
        field("lastSubmit", submits(records).lastOrNull()?.ts?.toLocalDate()?.toString()),
    )

    private fun heading(records: List<SubmissionRecord>): String {
        val title = titleOf(records) ?: "${records.first().lessonId}"
        return "\n# $title\n\n${obsidianTags(records)}\n${tagLinks(records)}"
    }

    /**
     * Wikilinks **in addition to** the hashtags above, because the two drive different features:
     * Obsidian's tag pane and search read hashtags, and its graph draws links (#229). Dropping
     * either would lose something that works today.
     *
     * Empty for a problem the catalog does not describe. An invented link would create an edge
     * to a tag note that no catalogued problem justifies, and the tag map's denominators come
     * from the catalog alone.
     */
    private fun tagLinks(records: List<SubmissionRecord>): String {
        val tags = tagsOf(records).orEmpty()
        if (tags.isEmpty()) return ""
        return "\nTags: " + tags.joinToString(" ") { "[[${layout.tagNoteLink(it)}]]" } + "\n"
    }

    // Our own labels are English (dev rules §12); the tag words come from the data and stay verbatim.
    private fun obsidianTags(records: List<SubmissionRecord>): String {
        val level = levelOf(records)?.let { "#Lv$it" }
        val tags = tagsOf(records).orEmpty().map { "#$it" }
        return (listOfNotNull("#programmers", level) + tags).joinToString(" ")
    }

    // Runs are counted in the frontmatter but get no row: a run is not an attempt (design §5.1).
    private fun history(records: List<SubmissionRecord>): String {
        val rows = submits(records).map { row(it) }
        if (rows.isEmpty()) return "\n$HISTORY\n\nNo submission yet.\n"
        return "\n$HISTORY\n\n$HEADER\n$SEPARATOR\n${rows.joinToString("\n")}\n"
    }

    private fun row(record: SubmissionRecord): String =
        "| ${record.attempt} | ${record.ts.format(TIME)} | ${verdictOf(record)} " +
            "| ${testcasesOf(record)} | ${elapsedOf(record.elapsedSec)} | ${diffOf(record)} |"

    /**
     * An unjudged submit reports its outcome; borrowing a neighbouring verdict would invent
     * data. A measured reason rides along (#74) — `UNKNOWN (cached result)` tells the reader
     * why the browser's scoreboard and this row disagree, where a bare UNKNOWN reads as a
     * capture bug.
     */
    private fun verdictOf(record: SubmissionRecord): String {
        val base = record.verdict?.name ?: record.outcome.name
        val reason = UnknownReason.of(record.outcome, record.errorText)?.label ?: return base
        return "$base ($reason)"
    }

    private fun testcasesOf(record: SubmissionRecord): String {
        val summary = record.tcSummary
        if (summary.total == 0) return NONE
        val counted = "${summary.passed}/${summary.total}"
        return if (summary.complete) counted else "$counted (partial)"
    }

    private fun elapsedOf(seconds: Long): String {
        if (seconds < 0) return NONE
        val minutes = seconds / 60
        if (minutes < 60) return "%dm%02ds".format(minutes, seconds % 60)
        return "%dh%02dm%02ds".format(minutes / 60, minutes % 60, seconds % 60)
    }

    private fun diffOf(record: SubmissionRecord): String = if (record.diffFromPrev.isNullOrBlank()) "no" else "yes"

    private fun titleOf(records: List<SubmissionRecord>): String? = latest(records) { it.title.ifBlank { null } }

    private fun levelOf(records: List<SubmissionRecord>): Int? = latest(records) { it.level }

    private fun tagsOf(records: List<SubmissionRecord>): List<String>? = latest(records) { it.tags.ifEmpty { null } }

    private fun submits(records: List<SubmissionRecord>): List<SubmissionRecord> =
        records.filter { it.action == GradingAction.SUBMIT }

    // The newest record that knew the value wins: a later record missing catalog metadata must
    // not erase what an earlier one already established.
    private fun <T : Any> latest(records: List<SubmissionRecord>, of: (SubmissionRecord) -> T?): T? =
        records.asReversed().firstNotNullOfOrNull(of)

    private fun field(name: String, value: String?): String? = value?.let { "$name: $it" }

    // Quoted uniformly: a problem title may contain a colon, which unquoted YAML reads as a mapping.
    private fun quoted(value: String?): String? =
        value?.let { """"${it.replace("\\", "\\\\").replace("\"", "\\\"")}"""" }

    private companion object {
        const val README = "README.md"
        const val HISTORY = "## Attempt history"
        const val HEADER = "| # | Time | Verdict | Testcases | Elapsed | Diff |"
        const val SEPARATOR = "|---|---|---|---|---|---|"
        const val NONE = "-"

        // Rendered at the offset the record carries, which is the clock the user submitted under.
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
