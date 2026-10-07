package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since

/**
 * What one `exam_prep` session covers (spec `2026-10-07-mistake-patterns-design` §4.4). Every field is
 * optional, and an absent one narrows nothing.
 *
 * [since] is checked, with the tools' own parser and in their words. A session prepared over the wrong
 * range would look right, so a bound that does not parse is refused before anything runs.
 *
 * [language] and [part] are taken as typed. The tools match them, and an unmatched one answers empty.
 * The one exception is a language that reads as a date, which is refused, because with positional
 * arguments that is the likely slip. [part] above all must not be checked: Claude Code splits prompt
 * arguments on spaces, so "GROUP BY" arrives as "GROUP", and 38 of the catalog's 49 part names have
 * a space. Instead of a check here refusing it, the paragraph tells the model to match a given part
 * against the labels `stats` returns ([[decisions/2026-10-07-exam-prep-asks-in-the-open]]).
 */
data class ExamPrepScope(val language: String? = null, val since: String? = null, val part: String? = null) {
    init {
        if (since != null) Since.from(since)
        require(language == null || !readsAsDate(language)) { positional(language) }
    }

    /** What the session covers, said the way the prompt opens. */
    fun paragraph(): String {
        if (narrowings().isEmpty()) return "Scope: everything on record."
        val lines = listOf("Scope: ${narrowings().joinToString(", ")}.", ONLY_REPAIR_STEPS)
        return (lines + listOfNotNull(part?.let { partWarning(it) })).joinToString("\n")
    }

    /** The `repair_steps` call that covers this scope, spelled as a call. */
    fun repairStepsCall(): String = "repair_steps(${arguments().joinToString(", ")})"

    private fun narrowings(): List<String> =
        listOfNotNull(language?.let { "language $it" }, since?.let { "since $it" }, part?.let { "part \"$it\"" })

    private fun arguments(): List<String> =
        listOfNotNull(language?.let { "language=$it" }, since?.let { "since=$it" }, part?.let { "part=$it" })

    private fun partWarning(given: String): String =
        "Some clients split arguments on spaces, so \"$given\" may be the start of a longer part name " +
            "(\"GROUP\" for \"GROUP BY\"). Match it against the part labels stats returns before narrowing " +
            "by it, and say which part you used."

    // The one positional slip worth catching: skip `language`, type the date first, and it lands here —
    // where it would narrow repair_steps to nothing instead of being refused.
    private fun readsAsDate(text: String): Boolean = runCatching { Since.from(text) }.isSuccess

    private fun positional(given: String?): String =
        "language \"$given\" reads as a date; the arguments are positional — language, since, part"

    private companion object {
        const val ONLY_REPAIR_STEPS =
            "Only repair_steps takes this scope; stats and list_problems answer over everything on record."
    }
}
