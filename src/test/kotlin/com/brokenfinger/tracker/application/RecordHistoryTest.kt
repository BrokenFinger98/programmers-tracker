package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Unit test for the corrected view of the submission log (dev rules §6.1) — no files, no
 * mocks, only stored lines in and resolved records out.
 *
 * The log is append-only, so a correction is a second line rather than an edit
 * ([[decisions/2026-08-05-write-serialization]] decision 2). What is asserted here is the
 * whole of what makes that safe: the newest line for a `(ts, captureKey)` pair wins, and it wins *in
 * place*, so appending a correction never reorders a problem's history.
 */
class RecordHistoryTest {
    @Test
    fun `a later line for the same capture key supersedes the earlier one`() {
        val pending = aSubmissionRecord(codePending = true, codePath = null, diffFromPrev = null)
        val attached = pending.copy(codePending = false, codePath = "problems/120804/attempts/002.java")

        val history = RecordHistory.of(stored(pending, attached))

        history shouldHaveSize 1
        history.single().isCodeAttached() shouldBe true
    }

    @Test
    fun `the corrected record keeps the position its first line had`() {
        val first = aSubmissionRecord(attempt = 1, captureKey = CaptureKey("aaaa000000000001"))
        val second = aSubmissionRecord(attempt = 2, captureKey = CaptureKey("aaaa000000000002"))
        val correctedFirst = first.copy(codePending = false)

        val history = RecordHistory.of(stored(first, second, correctedFirst))

        history.map { it.attempt } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `records that share no capture key all survive, oldest first`() {
        val first = aSubmissionRecord(attempt = 1, captureKey = CaptureKey("bbbb000000000001"))
        val second = aSubmissionRecord(attempt = 2, captureKey = CaptureKey("bbbb000000000002"))

        RecordHistory.of(stored(first, second)).map { it.attempt } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a line that is no record at all is left out rather than thrown on`() {
        val readable = aSubmissionRecord()
        val torn =
            RecordedSubmission(lessonId = 120804, action = null, attempt = 1, language = "java", ts = null, line = "{")

        val history = RecordHistory.of(listOf(torn) + stored(readable))

        history.map { it.captureKey } shouldContainExactly listOf(readable.captureKey)
    }

    @Test
    fun `an empty log resolves to an empty history`() {
        RecordHistory.of(emptyList()) shouldContainExactly emptyList()
    }

    /**
     * #343, measured 2026-10-06 on lesson 131537: five wrong SQL runs that returned the same
     * table share one capture key, because the key is derived from the grading's bytes. They
     * are five gradings, and the history must say five.
     */
    @Test
    fun `two gradings with identical bytes at different times are two records`() {
        val key = CaptureKey("cccc000000000001")
        val first = aSubmissionRecord(ts = OffsetDateTime.parse("2026-10-03T15:21:02+09:00"), captureKey = key)
        val second = aSubmissionRecord(ts = OffsetDateTime.parse("2026-10-03T15:23:52+09:00"), captureKey = key)

        val history = RecordHistory.of(stored(first, second))

        history.map { it.ts } shouldContainExactly listOf(first.ts, second.ts)
    }

    /** The rule #343 must not break: a correction carries its original's `ts` and still replaces it. */
    @Test
    fun `a correction still supersedes the grading it repeats when the key is shared`() {
        val key = CaptureKey("cccc000000000002")
        val earlier = aSubmissionRecord(ts = OffsetDateTime.parse("2026-10-03T15:21:02+09:00"), captureKey = key)
        val pending = aSubmissionRecord(
            ts = OffsetDateTime.parse("2026-10-03T15:23:52+09:00"),
            captureKey = key,
            codePending = true,
            codePath = null,
        )
        val attached = pending.copy(codePending = false, codePath = "problems/131537/Solution.sql")

        val history = RecordHistory.of(stored(earlier, pending, attached))

        history shouldHaveSize 2
        history.last().isCodeAttached() shouldBe true
    }

    /** Startup retry attaches an older pending run after a newer byte-identical one was recorded. */
    @Test
    fun `a correction appended after a later identical grading replaces only its own`() {
        val key = CaptureKey("cccc000000000003")
        val first = aSubmissionRecord(
            ts = OffsetDateTime.parse("2026-10-03T15:21:02+09:00"),
            captureKey = key,
            codePending = true,
            codePath = null,
        )
        val second = first.copy(ts = OffsetDateTime.parse("2026-10-03T15:23:52+09:00"))
        val firstAttached = first.copy(codePending = false, codePath = "problems/131537/Solution.sql")

        val history = RecordHistory.of(stored(first, second, firstAttached))

        history.map { it.ts } shouldContainExactly listOf(first.ts, second.ts)
        history.map { it.isCodeAttached() } shouldContainExactly listOf(true, false)
    }

    private fun stored(vararg records: SubmissionRecord): List<RecordedSubmission> =
        records.map { RecordedSubmission.ofReceived(SubmissionRecordJson.encode(it))!! }
}
