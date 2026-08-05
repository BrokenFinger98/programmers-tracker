package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
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
 * The page states only what the records know. No catalog is wired yet, so today every record
 * carries an empty title and no level, part or acceptance rate — those fields are then left out
 * entirely and the heading is the lesson id. An invented title would read exactly like a real
 * one, which is the silent-wrong-data outcome CLAUDE.md names as the worst possible.
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
        field("language", quoted(latest(records) { it.language.ifBlank { null } })),
    )

    // Catalog metadata is unset on every record written today. Omitted rather than defaulted:
    // `level: 0` would be indistinguishable from a genuine Lv0 problem.
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
        return "\n# $title\n\n${obsidianTags(records)}\n"
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

    /** An unjudged submit reports its outcome; borrowing a neighbouring verdict would invent data. */
    private fun verdictOf(record: SubmissionRecord): String = record.verdict?.name ?: record.outcome.name

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
