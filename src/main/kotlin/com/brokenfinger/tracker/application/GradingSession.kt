package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.ProblemExample
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.RatingChange
import com.brokenfinger.tracker.domain.Score
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.Verdict

/**
 * What one grading stream on one channel amounted to.
 *
 * [outcome] and [verdict] are deliberately separate ([[decisions/2026-08-05-failure-taxonomy]]):
 * the outcome says whether a conclusion was observed, the verdict says what it was. A
 * grading we failed to observe must never dilute the statistics of the ones we did, so
 * [verdict] is null for every outcome other than [Outcome.JUDGED].
 */
data class GradingSession(
    val kind: ProblemKind,
    val action: GradingAction?,
    val outcome: Outcome,
    val verdict: Verdict?,
    val testcases: List<TestcaseResult>,
    /**
     * True only when every id the stream announced actually arrived. False also covers the
     * case where nothing was announced: an unverifiable count is not a verified one
     * (design §4.2).
     */
    val testcasesComplete: Boolean,
    /** Run-path error text, already unescaped — the input that later promotes a submit. */
    val errorText: String?,
    /** The examples the stream announced (protocol §7) — what the runner is generated from. */
    val examples: List<ProblemExample> = emptyList(),
    /** What the judge scored, when it reported one. Null for every SQL grading (protocol §6). */
    val score: Score? = null,
    /** The rating this solve moved, when it reported one. Null for every SQL grading. */
    val rating: RatingChange? = null,
    /**
     * What every accepted frame said, in arrival order, including the frames nothing
     * recognised — those contribute an empty record and still hold their position, so a
     * stream that was only partly interpreted is visible as such.
     *
     * It holds the **facts, not the wire text**, and the reason is that the wire text is
     * already somewhere better. Stage 1 appends each frame to the raw log before any of this
     * runs, and the record points at that file (`SettledCapture.rawSessionId`,
     * `SubmissionRecord.rawPath`), so dev rules §2.4 — keep the original, reinterpretation
     * stays possible — is satisfied by bytes on disk that outlive the process. A second copy
     * in memory would add no durability, only a weaker archive for a caller to mistake for
     * the real one. Facts are also the only form that keeps message types out of
     * `application` at all ([[decisions/2026-08-05-protocol-dependency-direction]]).
     */
    val frames: List<GradingFrameFacts>,
)
