package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Outcome

/**
 * Why an UNKNOWN is unknown, when the error text is one we have measured.
 *
 * Exists for the person standing in front of two truths at once: the browser shows a cached
 * scoreboard reading 100.0 while the record says UNKNOWN, and until #74 the only explanation
 * sat in a JSONL field nobody opens. The commit subject, the README and MCP all print this —
 * one classifier, three consumers, no drift.
 *
 * **Narrow on purpose.** Exact match against measured strings, nothing fuzzy: a reworded
 * message is a different protocol, and the honest response to it is "unexplained", not a
 * guess that reads like knowledge. When Programmers rewords, classification degrades to plain
 * UNKNOWN with the original text still on the record — the same posture as preserving
 * `Unknown` messages instead of dropping them (dev rules §2.3).
 */
enum class UnknownReason(
    /** What the measured terminal frame carried, verbatim. */
    private val measuredText: String,
    /** The short human label every consumer prints. */
    val label: String,
) {
    /**
     * Programmers served a previous grading of the same code instead of judging again:
     * `submit/start` then a terminal `submit/error` carrying this message, and the browser
     * renders the *cached* scoreboard from its own request — the broadcast never carries
     * those numbers. Measured on lessons 181951 and 181952 (protocol §13.2, verification
     * log entries 2 and 16).
     */
    CACHED_RESULT("같은 코드로 채점한 결과가 있습니다.", "cached result"),
    ;

    companion object {
        /**
         * The reason, or null when there is none to give. Null is an answer here — it means
         * "we have not measured this failure", which the caller must surface as absence
         * rather than paper over.
         */
        fun of(outcome: Outcome, errorText: String?): UnknownReason? {
            if (outcome != Outcome.UNKNOWN) return null
            return entries.firstOrNull { it.measuredText == errorText }
        }
    }
}
