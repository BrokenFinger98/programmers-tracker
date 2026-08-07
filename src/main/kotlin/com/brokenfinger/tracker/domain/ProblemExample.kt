package com.brokenfinger.tracker.domain

import kotlinx.serialization.Serializable

/**
 * One example the judge runs on Run Code, exactly as the wire carried it.
 *
 * The `run` `start` frame ships these inline (protocol §7) and they are the whole input to
 * the runner generator (#37): [input] is the argument list of a `solution(...)` problem or
 * the stdin text of a `main` one, [expected] the value or stdout the judge compares against.
 *
 * **Strings on purpose.** The values are JSON-*like*, not JSON — a main-style expected
 * output carries a raw newline inside its quotes (§7.1) — and parsing them into types here
 * would bake one interpretation into every record. The generator parses at generation time,
 * against the user's own solution signature, and refuses what it cannot parse; the record
 * keeps what was measured.
 */
@Serializable
data class ProblemExample(val input: String?, val expected: String?) {
    companion object {
        /**
         * Lenient, per dev rules §4: these come off the wire. An entry with neither side is
         * dropped; one with a single side is kept — half a measured example still says
         * something the generator can refuse on. Order is preserved, because the judge's
         * per-case frames identify themselves by index into this list.
         */
        fun ofReceived(pairs: List<Pair<String?, String?>>): List<ProblemExample> =
            pairs.mapNotNull { (input, expected) ->
                if (input == null && expected == null) return@mapNotNull null
                ProblemExample(input, expected)
            }
    }
}
