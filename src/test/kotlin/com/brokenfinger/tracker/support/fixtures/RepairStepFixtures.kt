package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.CodedGrading
import com.brokenfinger.tracker.domain.calc.KeptCode
import java.time.OffsetDateTime

// Object mothers for repair steps (dev rules §6.4, spec 2026-10-07 §4.3). A run's code comes from
// runs.jsonl and a submit's from its attempt file, so the two builders differ in what they own:
// a run carries no codePath here, a submit carries the path the store would have written.

fun aRun(
    at: String,
    verdict: Verdict? = Verdict.WRONG,
    outcome: Outcome = Outcome.JUDGED,
    lessonId: Long = 120804,
    language: String = "java",
): SubmissionRecord = aSubmissionRecord(
    ts = OffsetDateTime.parse(at),
    lessonId = lessonId,
    language = language,
    action = GradingAction.RUN,
    attempt = 0,
    outcome = outcome,
    verdict = verdict,
    score = null,
    rating = null,
    testcases = listOf(aTestcaseResult(passed = verdict == Verdict.PASS, msg = null, runTime = null)),
    codePath = null,
    diffFromPrev = null,
)

fun aSubmit(
    at: String,
    attempt: Int = 1,
    verdict: Verdict? = Verdict.PASS,
    lessonId: Long = 120804,
    language: String = "java",
): SubmissionRecord = aSubmissionRecord(
    ts = OffsetDateTime.parse(at),
    lessonId = lessonId,
    language = language,
    action = GradingAction.SUBMIT,
    attempt = attempt,
    verdict = verdict,
    testcases = listOf(aTestcaseResult(passed = verdict == Verdict.PASS, msg = null, runTime = null)),
    codePath = "problems/$lessonId-두-수의-곱-구하기/attempts/%03d.%s"
        .format(attempt, RecordLayout.extensionOf(language)),
    diffFromPrev = null,
)

fun aKeptCode(text: String = "select 1", fetchedAt: String? = null): KeptCode =
    KeptCode(text, fetchedAt?.let(OffsetDateTime::parse))

fun aCodedGrading(record: SubmissionRecord, code: String? = "select 1", late: Boolean = false): CodedGrading =
    CodedGrading(record, code?.let { aKeptCode(it) }, late)
