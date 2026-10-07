package com.brokenfinger.tracker.adapter.mcp

/**
 * The text `exam_prep` hands the client's model (spec `2026-10-07-mistake-patterns-design` §4.4).
 *
 * The one place this server asks for interpretation, and it asks in the open. The tools still count and
 * name nothing ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]). This is the text the
 * learner sends their own model through a slash command, asking it to do the naming.
 *
 * **It carries its own readings.** The server instructions are nearly full (#353), so what a pre-exam
 * session must not misread travels here. Claude Code cuts instructions and tool descriptions at 2,048
 * characters but not a prompt's text (2.1.285, read from the client), so this has no budget test. It
 * stays short because it is pasted into a conversation every time it runs.
 */
object ExamPrepPrompt {
    const val NAME = "exam_prep"

    fun text(scope: ExamPrepScope): String =
        listOf(OPENING, scope.paragraph(), ASKED, steps(scope), READINGS).joinToString("\n\n")

    private fun steps(scope: ExamPrepScope): String =
        listOf(STATS_STEP, "2. Call ${scope.repairStepsCall()}. $REPAIR_STEP", LATER_STEPS).joinToString("\n")

    private const val OPENING =
        "Prepare me for a coding test from my own Programmers records, using this server's tools."

    private const val ASKED = "The tools count and name nothing. Here the naming is asked for: find my " +
        "recurring mistakes, and show the records behind each one."

    private const val STATS_STEP = "1. Call stats(groupBy=part), then stats(groupBy=level). Note where passing " +
        "took the most runs and submits."

    private const val REPAIR_STEP = "When an answer says `truncated`, call again with `limit` set to its " +
        "`total`; if that is too many to read, narrow with `since` and say so. Group the steps into recurring " +
        "patterns — an argument order, a method name, an off-by-one bound, a missing table alias, a syntax " +
        "slip. Name each pattern by what its diffs show, cite the record ids behind it, and say how many " +
        "problems it spans. A pattern seen once is not a pattern."

    private val LATER_STEPS = listOf(
        "3. For each pattern: the problems to re-solve (its steps' lessonId and title), and up to three " +
            "problems from list_problems(status=untouched, part=<a part its steps come from>).",
        "4. For each pattern: two or three short drills aimed at exactly that point — if the diffs keep " +
            "fixing substring bounds, \"take the 3rd–4th characters with substring\".",
        "5. Last, what the records could not support.",
    ).joinToString("\n")

    private val READINGS = listOf(
        "Readings that are easy to get wrong:",
        "- A step shows what changed, not what was wrong. Where the diff alone cannot tell, say so.",
        "- A run is not an attempt; stats counts submits only.",
        "- Absent is not zero: a step without `diff` says why in `noDiff`.",
        "- Run code is kept by tracker versions from 2026-10-07 on, so an earlier step that starts or ends " +
            "at a run has no diff. Submit code was always kept.",
        "- `codeLate: true` marks code that may belong to the next grading; build no pattern on it alone.",
        "- `incompleteHistory` means gradings were captured that no record represents: say the counts have " +
            "holes.",
    ).joinToString("\n")
}
