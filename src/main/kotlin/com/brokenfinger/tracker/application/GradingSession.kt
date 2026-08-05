package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.protocol.message.SubmitMessage

/**
 * What one grading stream on one channel amounted to.
 *
 * [outcome] and [verdict] are deliberately separate ([[decisions/2026-08-05-failure-taxonomy]]):
 * the outcome says whether a conclusion was observed, the verdict says what it was. A
 * grading we failed to observe must never dilute the statistics of the ones we did, so
 * [verdict] is null for every outcome other than [Outcome.JUDGED].
 *
 * [frames] carries every message accepted, in arrival order and including the ones nothing
 * recognised — storing only the interpretation would make later reinterpretation impossible
 * (dev rules §2.4).
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
    val frames: List<SubmitMessage>,
)
