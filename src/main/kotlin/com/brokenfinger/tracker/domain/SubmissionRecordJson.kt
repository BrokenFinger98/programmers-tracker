package com.brokenfinger.tracker.domain

import kotlinx.serialization.json.Json

/**
 * The JSONL codec for [SubmissionRecord] — one record, one line.
 *
 * Decoding is lenient by construction (dev rules §4): a field a later writer version adds
 * must not make the line unreadable, because that line is both the attempt-number authority
 * and the dedup index ([[decisions/2026-08-05-write-serialization]]). Losing a record to a
 * schema quibble is strictly worse than ignoring a field we do not understand.
 */
object SubmissionRecordJson {
    private val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** Encodes to a single line. Newlines inside values are escaped by JSON itself. */
    fun encode(record: SubmissionRecord): String = format.encodeToString(record)

    fun decode(line: String): SubmissionRecord = format.decodeFromString(line)
}
