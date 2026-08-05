package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The code files derived from a record — `Solution.<ext>`, `attempts/NNN.<ext>` and the diff
 * against the previous attempt (design §5.1).
 *
 * **`run` and `submit` are not treated alike** (design §5.1, "How run and submit are handled
 * differently"). `run` is pressed dozens of times while writing code, so it refreshes the
 * solution file and stops there. Only a `submit` owns an attempt number, so only a `submit`
 * gets an attempt copy and a diff; a run that wrote one would overwrite an attempt that is
 * already history.
 *
 * Every write is temp-then-replace via [AtomicStateFile], so a reader — a git commit, an
 * editor, a later re-analysis — never sees half a solution, and writing the same code twice
 * leaves the same bytes.
 */
class CodeArtifacts(recordRoot: Path, private val records: RecordStore) {
    private val layout = RecordLayout(recordRoot)

    /** The latest code per language, refreshed on **both** run and submit (design §5.1). */
    fun writeLatest(record: SubmissionRecord, code: String): Path {
        val name = "$SOLUTION.${RecordLayout.extensionOf(record.language)}"
        return written(layout.problemDirectory(record.lessonId, record.title).resolve(name), code)
    }

    /**
     * The attempt copy — `attempts/NNN.<ext>`. Null for a run, which owns no attempt number
     * of its own.
     */
    fun writeAttempt(record: SubmissionRecord, code: String): Path? {
        if (!ownsAttemptFile(record)) return null
        return written(attemptFile(record, record.attempt), code)
    }

    /**
     * A unified diff of [code] against **the previous attempt in the same language**
     * (design §5.1). Numbering is monotonic across languages, so the previous attempt is not
     * necessarily the previous *number*: diffing Java against SQL would be noise rather than
     * "what was missed".
     *
     * The output is capped at [MAX_DIFF_LINES] lines and ends with a truncation marker beyond
     * it, because this string is inlined into every record — an uncapped diff of a large file
     * would bloat `log/submissions.jsonl` on its own.
     *
     * Null rather than an invented diff whenever there is nothing honest to compare against:
     * a run, the first attempt in that language, a previous attempt whose file is missing
     * (diffing against an empty file would report the whole solution as added), unchanged
     * code, or a file too large to diff cheaply.
     */
    fun diffFromPrev(record: SubmissionRecord, code: String): String? {
        if (!ownsAttemptFile(record)) return null
        val previous = previousInSameLanguage(record) ?: return null
        val before = readAttempt(record, previous) ?: return null
        val after = linesOf(normalized(code))
        return UnifiedDiff.of(before, after, nameOf(record, previous), nameOf(record, record.attempt))
    }

    // The previous attempt comes from the submission log, the single authority for attempt
    // numbers (design §4.5). Scanning `attempts/` would make the directory a second source of
    // truth, and the language a record was written in only exists in the log anyway — two
    // languages can share one extension (`mysql` and `oracle` are both `.sql`).
    private fun previousInSameLanguage(record: SubmissionRecord): Int? = records.read()
        .filter { it.lessonId == record.lessonId && it.action == GradingAction.SUBMIT }
        .filter { it.language.equals(record.language, ignoreCase = true) }
        .map { it.attempt }
        .filter { it in FIRST_ATTEMPT until record.attempt }
        .maxOrNull()

    private fun readAttempt(record: SubmissionRecord, attempt: Int): List<String>? {
        val file = attemptFile(record, attempt)
        if (!Files.isRegularFile(file)) return missing(attempt)
        // Decoded with replacement rather than reported: one torn byte must not cost the diff.
        return linesOf(String(Files.readAllBytes(file), CHARSET))
    }

    private fun missing(attempt: Int): List<String>? {
        logger.warn("Attempt {} has no stored code; recording no diff rather than inventing one", attempt)
        return null
    }

    private fun written(file: Path, code: String): Path {
        AtomicStateFile(file).write(normalized(code))
        return file
    }

    private fun ownsAttemptFile(record: SubmissionRecord): Boolean =
        record.action == GradingAction.SUBMIT && record.attempt >= FIRST_ATTEMPT

    private fun attemptFile(record: SubmissionRecord, attempt: Int): Path =
        layout.attemptFile(record.lessonId, record.title, attempt, record.language)

    /** The name a diff header shows — relative to the problem directory, as git would. */
    private fun nameOf(record: SubmissionRecord, attempt: Int): String =
        "$ATTEMPTS/${attemptFile(record, attempt).fileName}"

    companion object {
        /** The diff is inlined into every record line, so a long one is cut off here. */
        const val MAX_DIFF_LINES = 400

        /** Past this the LCS table stops being cheap, and such a file yields no diff at all. */
        const val MAX_DIFF_INPUT_LINES = 2000

        private const val FIRST_ATTEMPT = 1
        private const val SOLUTION = "Solution"
        private const val ATTEMPTS = "attempts"
        private val CHARSET = StandardCharsets.UTF_8
        private val logger = LoggerFactory.getLogger(CodeArtifacts::class.java)

        // Exactly one trailing newline, whether or not the fetched code ended in one: an
        // ordinary text file, and a last line that does not move between attempts.
        private fun normalized(code: String): String = code.trimEnd('\n') + "\n"

        private fun linesOf(text: String): List<String> {
            val body = text.removeSuffix("\n")
            if (body.isEmpty()) return emptyList()
            return body.split("\n")
        }
    }
}

/**
 * A minimal unified diff over lines, backed by an LCS table.
 *
 * Hand-written rather than taken as a dependency: two solution files of a few dozen lines are
 * all this will ever see, and a diff library would buy supply-chain surface for nothing.
 */
private object UnifiedDiff {
    private const val CONTEXT = 3
    private val logger = LoggerFactory.getLogger(UnifiedDiff::class.java)

    /** Null when nothing changed, or when either side is too large to diff cheaply. */
    fun of(old: List<String>, new: List<String>, oldName: String, newName: String): String? {
        if (tooLarge(old, new)) return null
        val ops = editScript(old, new)
        val ranges = hunkRanges(ops)
        if (ranges.isEmpty()) return null
        val header = listOf("--- a/$oldName", "+++ b/$newName")
        return capped(header + ranges.flatMap { hunk(ops, it) }).joinToString("\n")
    }

    private fun tooLarge(old: List<String>, new: List<String>): Boolean {
        val limit = CodeArtifacts.MAX_DIFF_INPUT_LINES
        if (old.size <= limit && new.size <= limit) return false
        logger.warn("Skipped a diff of {} against {} lines — beyond the {} line limit", old.size, new.size, limit)
        return true
    }

    private fun capped(lines: List<String>): List<String> {
        val limit = CodeArtifacts.MAX_DIFF_LINES
        if (lines.size <= limit) return lines
        return lines.take(limit) + "... diff truncated at $limit lines"
    }

    private fun hunk(ops: List<Op>, range: IntRange): List<String> {
        val header = "@@ -${span(ops, range, Change.ADD)} +${span(ops, range, Change.REMOVE)} @@"
        return listOf(header) + range.map { "${ops[it].change.marker}${ops[it].text}" }
    }

    // "start,count" over the lines one side actually has: an addition does not exist on the
    // old side, a removal does not exist on the new one. An empty hunk starts at the line
    // before it, which is what `+ count.coerceAtMost(1)` says without a branch.
    private fun span(ops: List<Op>, range: IntRange, absent: Change): String {
        val before = (0 until range.first).count { ops[it].change != absent }
        val count = range.count { ops[it].change != absent }
        return "${before + count.coerceAtMost(1)},$count"
    }

    // Every changed line pulls CONTEXT lines around it into a hunk; hunks that touch merge.
    private fun hunkRanges(ops: List<Op>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        ops.indices
            .filter { ops[it].change != Change.KEEP }
            .forEach { merge(ranges, (it - CONTEXT)..(it + CONTEXT)) }
        return ranges.map { it.first.coerceAtLeast(0)..it.last.coerceAtMost(ops.lastIndex) }
    }

    private fun merge(ranges: MutableList<IntRange>, range: IntRange) {
        val last = ranges.lastOrNull()
        if (last == null || range.first > last.last + 1) {
            ranges += range
            return
        }
        ranges[ranges.lastIndex] = last.first..range.last
    }

    // Walks the table from (0,0). Whichever side runs out first leaves a remainder, which can
    // only be a pure removal or a pure addition.
    private fun editScript(old: List<String>, new: List<String>): List<Op> {
        val ops = mutableListOf<Op>()
        val end = walk(lcsTable(old, new), old, new, ops)
        ops += old.drop(end.first).map { Op(Change.REMOVE, it) }
        ops += new.drop(end.second).map { Op(Change.ADD, it) }
        return ops
    }

    private fun walk(
        table: Array<IntArray>,
        old: List<String>,
        new: List<String>,
        ops: MutableList<Op>,
    ): Pair<Int, Int> {
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < old.size && newIndex < new.size) {
            val op = stepAt(table, old, new, oldIndex, newIndex)
            ops += op
            if (op.change != Change.ADD) oldIndex++
            if (op.change != Change.REMOVE) newIndex++
        }
        return oldIndex to newIndex
    }

    // A removal is preferred over an addition on a tie, which is what makes a replaced line
    // read as `-` then `+`.
    private fun stepAt(table: Array<IntArray>, old: List<String>, new: List<String>, i: Int, j: Int): Op {
        if (old[i] == new[j]) return Op(Change.KEEP, old[i])
        if (table[i + 1][j] >= table[i][j + 1]) return Op(Change.REMOVE, old[i])
        return Op(Change.ADD, new[j])
    }

    // table[i][j] = the length of the longest common subsequence of old[i..] and new[j..].
    private fun lcsTable(old: List<String>, new: List<String>): Array<IntArray> {
        val table = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                table[i][j] = lengthAt(table, old, new, i, j)
            }
        }
        return table
    }

    private fun lengthAt(table: Array<IntArray>, old: List<String>, new: List<String>, i: Int, j: Int): Int {
        if (old[i] == new[j]) return table[i + 1][j + 1] + 1
        return maxOf(table[i + 1][j], table[i][j + 1])
    }

    private enum class Change(val marker: Char) {
        KEEP(' '),
        REMOVE('-'),
        ADD('+'),
    }

    private data class Op(val change: Change, val text: String)
}
