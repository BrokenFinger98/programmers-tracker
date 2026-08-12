package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.RecordedSubmission
import com.brokenfinger.tracker.domain.GradingAction

/**
 * Object mothers for submission-log lines (dev rules §6.4). The JSONL shape is design §5.2;
 * only the fields the store itself reads are spelled out, the rest is padding that proves
 * unknown keys survive a round trip.
 */
fun aRecordLine(
    lessonId: Long = 120804,
    action: String = "submit",
    attempt: Int = 1,
    language: String = "java",
    extra: String = ""","outcome":"JUDGED","verdict":"PASS"""",
): String = """{"lessonId":$lessonId,"action":"$action","attempt":$attempt,"language":"$language"$extra}"""

fun aRecord(
    lessonId: Long = 120804,
    action: GradingAction? = GradingAction.SUBMIT,
    attempt: Int = 1,
    language: String? = "java",
    ts: java.time.OffsetDateTime? = java.time.OffsetDateTime.parse("2026-08-04T14:23:01+09:00"),
): RecordedSubmission = RecordedSubmission(
    lessonId = lessonId,
    action = action,
    attempt = attempt,
    language = language,
    ts = ts,
    line = aRecordLine(lessonId, action?.name?.lowercase() ?: "unknown", attempt, language ?: "java"),
)
