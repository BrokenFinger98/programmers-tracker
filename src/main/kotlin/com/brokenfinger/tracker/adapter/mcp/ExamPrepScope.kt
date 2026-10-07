package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import kotlinx.serialization.json.JsonPrimitive

/**
 * What one `exam_prep` session covers (spec `2026-10-07-mistake-patterns-design` §4.4). Every field is
 * optional, and an absent one narrows nothing.
 *
 * [since] is checked, with the tools' own parser and in their words. A session prepared over the wrong
 * range would look right, so a bound that does not parse is refused before anything runs. The refusal
 * names the positional order, because `/exam_prep mysql SELECT` is the likely slip.
 *
 * [language] and [part] are taken as typed. The tools match them in full, and an unmatched one answers
 * empty. So whenever something narrows, the paragraph tells the model that an empty answer is not an
 * absence of mistakes. The one refusal is a language that reads as a date: with positional arguments,
 * that is the date typed first. [part] above all must not be checked: Claude Code splits prompt
 * arguments on spaces, so "GROUP BY" arrives as "GROUP", and 38 of the catalog's 49 part names have a
 * space. The paragraph tells the model to match a given part against the part keys `stats` returns
 * ([[decisions/2026-10-07-exam-prep-asks-in-the-open]]).
 *
 * Values are rendered as JSON strings: "String, Date" is one part name, not two arguments.
 */
data class ExamPrepScope(val language: String? = null, val since: String? = null, val part: String? = null) {
    init {
        if (language != null) require(!readsAsDate(language)) { positional("language \"$language\" reads as a date") }
        if (since != null) require(readsAsDate(since)) { positional(Since.FORMAT) }
    }

    /** What the session covers, said the way the prompt opens. */
    fun paragraph(): String {
        if (given().isEmpty()) return "Scope: everything on record."
        val lines = listOf("Scope: ${rendered(" ")}.", ONLY_REPAIR_STEPS, EMPTY_IS_NOT_CLEAN)
        return (lines + listOfNotNull(part?.let { partWarning(it) })).joinToString("\n")
    }

    /** The `repair_steps` call that covers this scope, spelled as a call. */
    fun repairStepsCall(): String = "repair_steps(${rendered("=")})"

    private fun given(): List<Pair<String, String>> =
        listOfNotNull(language?.let { "language" to it }, since?.let { "since" to it }, part?.let { "part" to it })

    private fun rendered(separator: String): String =
        given().joinToString(", ") { (name, value) -> "$name$separator${quoted(value)}" }

    private fun partWarning(given: String): String =
        "Some clients split arguments on spaces, so ${quoted(given)} may be the start of a longer part name " +
            "(\"GROUP\" for \"GROUP BY\"). Match it against the part keys stats(groupBy=part) returns, call " +
            "repair_steps with the full name, and say which part you used."

    private fun readsAsDate(text: String): Boolean = runCatching { Since.from(text) }.isSuccess

    private fun positional(problem: String): String = "$problem; the arguments are positional — language, since, part"

    private fun quoted(value: String): String = JsonPrimitive(value).toString()

    private companion object {
        const val ONLY_REPAIR_STEPS =
            "Only repair_steps takes this scope; stats and list_problems answer over everything on record."
        const val EMPTY_IS_NOT_CLEAN = "An empty answer under this scope is not an absence of mistakes: say so, " +
            "and say which argument may not match what the records hold."
    }
}
