package com.brokenfinger.tracker.adapter.mcp

/**
 * What `get_problem(include=…)` can add to a problem's items (spec 2026-10-07 §4.3).
 *
 * A closed set rather than strings, because three places must agree on it — the tool schema that
 * offers it, the argument check that refuses anything else, and the writer that puts the code where it
 * belongs — and with strings a misspelling in any one of them is a value that is asked for and
 * silently ignored, which reads as an answer with no code in it.
 */
enum class ProblemInclude {
    /** Each submit's kept code. */
    CODE,

    /** Each run's kept code, when it was attached, and its diff from the grading before it. */
    RUNS,
    ;

    /** The spelling used on the wire, which is also what the tool schema enumerates. */
    fun wireName(): String = name.lowercase()

    companion object {
        /**
         * Strict (dev rules §4) — a value we cannot honour is refused, naming what is offered, never
         * dropped: an `include` that quietly asked for nothing would be answered as though it had.
         */
        fun from(raw: String): ProblemInclude = entries.firstOrNull { it.wireName() == raw.trim().lowercase() }
            ?: throw IllegalArgumentException("include takes a list drawn from: ${wireNames().joinToString()}")

        fun wireNames(): List<String> = entries.map { it.wireName() }
    }
}
