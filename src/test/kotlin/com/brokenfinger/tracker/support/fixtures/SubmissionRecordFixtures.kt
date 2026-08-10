package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.RatingChange
import com.brokenfinger.tracker.domain.Score
import com.brokenfinger.tracker.domain.SensorObservation
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.TestcaseSummary
import com.brokenfinger.tracker.domain.Verdict
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

// Object mothers (dev rules §6.4). Defaults describe the passing algorithm submission of
// design §5.2; tests override only what differs. Korean literals are measured protocol
// values kept verbatim (protocol doc §7).

fun aSubmissionRecord(
    ts: OffsetDateTime = OffsetDateTime.parse("2026-08-04T14:23:01+09:00"),
    lessonId: Long = 120804,
    title: String = "두 수의 곱 구하기",
    level: Int? = 0,
    part: String? = "코딩테스트 입문",
    acceptanceRate: Int? = 91,
    tags: List<String> = listOf("구현"),
    language: String = "java",
    action: GradingAction = GradingAction.SUBMIT,
    attempt: Int = 2,
    elapsedSec: Long = 847,
    sincePrevSec: Long? = 312,
    hintLevel: Int = 0,
    captureKey: CaptureKey = aCaptureKey(),
    outcome: Outcome = Outcome.JUDGED,
    verdict: Verdict? = Verdict.PASS,
    score: Score? = Score(user = "100.0", perfect = "100.0"),
    testcases: List<TestcaseResult> = listOf(aTestcaseResult()),
    tcSummary: TestcaseSummary = TestcaseSummary.of(testcases, complete = true),
    rating: RatingChange? = RatingChange.of(old = 1371, new = 1372),
    rawPath: String = "problems/120804-two-numbers/attempts/002.raw.jsonl",
    codePath: String? = "problems/120804-two-numbers/attempts/002.java",
    codePending: Boolean = false,
    diffFromPrev: String? = "@@ -3,1 +3,1 @@\n-        return num1 * num2;\n+        return (long) num1 * num2;",
    errorText: String? = null,
    sensor: SensorObservation? = null,
) = SubmissionRecord(
    ts = ts,
    lessonId = lessonId,
    title = title,
    level = level,
    part = part,
    acceptanceRate = acceptanceRate,
    tags = tags,
    language = language,
    action = action,
    attempt = attempt,
    elapsedSec = elapsedSec,
    sincePrevSec = sincePrevSec,
    hintLevel = hintLevel,
    captureKey = captureKey,
    outcome = outcome,
    verdict = verdict,
    score = score,
    testcases = testcases,
    tcSummary = tcSummary,
    rating = rating,
    rawPath = rawPath,
    codePath = codePath,
    codePending = codePending,
    diffFromPrev = diffFromPrev,
    errorText = errorText,
    sensor = sensor,
)

/**
 * What the browser extension saw. Absent by default, because absent is the normal case: a
 * record written without the extension carries none, and a reader must be able to tell that
 * from a reading of zero ([[decisions/2026-08-10-sensor-observations]]).
 */
fun aSensorObservation(focusedSec: Long = 612, sawQuestions: Boolean = false) =
    SensorObservation(focusedSec = focusedSec, sawQuestions = sawQuestions)

/**
 * A database submission. Everything protocol doc §6 says the SQL path never sends is null:
 * no score, no rating, and no per-testcase timing or memory.
 */
fun aSqlSubmissionRecord() = aSubmissionRecord(
    lessonId = 131528,
    title = "인기있는 아이스크림",
    part = "SELECT",
    tags = emptyList(),
    language = "mysql",
    captureKey = CaptureKey("0f1e2d3c4b5a6978"),
    score = null,
    testcases = listOf(aTestcaseResult(msg = null, runTime = null, memorySize = null)),
    rating = null,
    codePath = "problems/131528-popular-ice-cream/attempts/001.sql",
)

/**
 * A capture key that is different on every call, because in a real repository it is.
 *
 * The key is derived from the frame that ended one grading, so two gradings never share one
 * — and since corrections are resolved newest-per-key
 * ([[decisions/2026-08-06-record-corrections-by-append]]), a fixture that handed every record
 * the same key would collapse a whole log into a single record. That is not a hypothetical:
 * a fixed default here made six reader tests pass for the wrong reason until the reader
 * started resolving corrections.
 *
 * A test that means "the same grading, corrected" says so explicitly — by passing a key, or
 * by `copy()`ing a record, which keeps it.
 */
fun aCaptureKey(): CaptureKey = CaptureKey("%016x".format(captureKeys.incrementAndGet()))

private val captureKeys = AtomicLong()
