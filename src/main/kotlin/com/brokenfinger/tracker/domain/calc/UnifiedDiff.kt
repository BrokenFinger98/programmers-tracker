package com.brokenfinger.tracker.domain.calc

/**
 * A minimal unified diff over lines, backed by an LCS table.
 *
 * Lived privately in `adapter/store/CodeArtifacts.kt` until repair steps needed it
 * (spec 2026-10-07 section 4.3): it was always a pure function of two texts, and [RepairSteps] - a
 * calculator that may import nothing outside the domain - must produce the same diff the attempt
 * files carry, not a second one that formats a change differently.
 *
 * Hand-written rather than taken as a dependency: two solution files of a few dozen lines are
 * all this will ever see, and a diff library would buy supply-chain surface for nothing.
 */
object UnifiedDiff {
    /** The diff is inlined into records and answers, so a long one is cut off here. */
    const val MAX_LINES = 400

    /** Past this the LCS table stops being cheap, and such a file yields no diff at all. */
    const val MAX_INPUT_LINES = 2000

    /**
     * The last line of a diff that hit [MAX_LINES]. Attempt files' `diffFromPrev` already carry
     * it, so the text does not change; [isTruncated] is the one place that recognises it.
     */
    const val TRUNCATION_MARKER = "... diff truncated at $MAX_LINES lines"

    private const val CONTEXT = 3

    /** Null when nothing changed, or when either side is too large to diff cheaply. */
    fun of(old: List<String>, new: List<String>, oldName: String, newName: String): String? {
        if (!fits(old, new)) return null
        val ops = editScript(old, new)
        val ranges = hunkRanges(ops)
        if (ranges.isEmpty()) return null
        val header = listOf("--- a/$oldName", "+++ b/$newName")
        return capped(header + ranges.flatMap { hunk(ops, it) }).joinToString("\n")
    }

    /** Whether both sides are small enough for [of] to diff them. */
    fun fits(old: List<String>, new: List<String>): Boolean = maxOf(old.size, new.size) <= MAX_INPUT_LINES

    /**
     * Whether [diff], as [of] returned it, was cut at [MAX_LINES]. Exact rather than a guess: every
     * line of the code being compared carries a marker character (' ', '-' or '+') in front of it, so
     * only the cap's own line can follow a line break bare.
     */
    fun isTruncated(diff: String): Boolean = diff.endsWith("\n$TRUNCATION_MARKER")

    private fun capped(lines: List<String>): List<String> {
        if (lines.size <= MAX_LINES) return lines
        return lines.take(MAX_LINES) + TRUNCATION_MARKER
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
