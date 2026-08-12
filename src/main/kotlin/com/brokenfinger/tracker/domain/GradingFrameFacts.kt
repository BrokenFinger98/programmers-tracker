package com.brokenfinger.tracker.domain

/**
 * Everything one frame of a grading stream said, in domain terms — the whole of what
 * assembly reads out of a broadcast
 * ([[decisions/2026-08-05-protocol-dependency-direction]] decision 2).
 *
 * A **record of facts** rather than a sealed hierarchy of event kinds, because one frame
 * contributes several orthogonal things at once: an algorithm `start` names the action,
 * announces how many cases are coming and opens the grading, and a database `finish` both
 * ends the stream and carries the only testcase result there is (protocol doc §6). One
 * event per frame would force the assembler to branch on the very type distinction this
 * boundary exists to hide, and the branches would have to be kept in step with the wire
 * format — which is the coupling that made this a separate issue.
 *
 * Every fact is absent-able, for the reason every protocol field is nullable (dev rules
 * §2.2): a frame nothing recognised is still kept, and simply reports nothing.
 */
data class GradingFrameFacts(
    val action: GradingAction? = null,
    val terminalKind: TerminalKind? = null,
    val testcase: TestcaseResult? = null,
    /** Ids the stream promised to grade; empty when this frame promised none. */
    val announcedTestcaseIds: List<Long> = emptyList(),
    /** How many cases were promised, for the streams that promise a count instead of ids. */
    val announcedTestcaseCount: Int? = null,
    /**
     * The example pairs a `run` `start` ships inline (protocol §7) — the runner generator's
     * whole input (#37). Empty for every frame that announces none.
     */
    val announcedExamples: List<ProblemExample> = emptyList(),
    /** Run-path error text, already unescaped — the input that later promotes a submit. */
    val errorText: String? = null,
    /** Whether this frame opens a grading rather than continuing one. */
    val startsGrading: Boolean = false,
    /**
     * Whether this frame belongs to a **documented action that never grades anything** —
     * saving, and resetting the editor to its template (protocol doc §8). The channel carries
     * them alongside gradings and they can never become records (#215).
     *
     * It is a separate fact from a null [action] because the two must not be confused: null
     * means *we did not recognise it*, which is the signal that Programmers changed something
     * and is worth raising an alarm over. This means *we recognise it and it is not a grading*,
     * which is worth nothing at all. Counting the second as the first told every MCP client
     * that the solving history had a hole in it because the learner had pressed reset.
     */
    val outsideGrading: Boolean = false,
    /**
     * What the judge scored, when the frame reported it (#193).
     *
     * Parsed since the beginning and dropped here: there was no field for it, so 76 records in a
     * row said `score: null` while the record's own KDoc explained that null as a SQL-only fact.
     */
    val score: Score? = null,
    /** The rating the solve moved, when the frame reported it — the clearest progress signal. */
    val rating: RatingChange? = null,
) {
    /** Only a submit may be promoted by a preceding run's error text (design §3.3). */
    fun isSubmit(): Boolean = action == GradingAction.SUBMIT
}
