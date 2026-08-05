package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit test for the corrected view of the submission log (dev rules §6.1) — no files, no
 * mocks, only stored lines in and resolved records out.
 *
 * The log is append-only, so a correction is a second line rather than an edit
 * ([[decisions/2026-08-05-write-serialization]] decision 2). What is asserted here is the
 * whole of what makes that safe: the newest line for a capture key wins, and it wins *in
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
        val torn = RecordedSubmission(lessonId = 120804, action = null, attempt = 1, language = "java", line = "{")

        val history = RecordHistory.of(listOf(torn) + stored(readable))

        history.map { it.captureKey } shouldContainExactly listOf(readable.captureKey)
    }

    @Test
    fun `an empty log resolves to an empty history`() {
        RecordHistory.of(emptyList()) shouldContainExactly emptyList()
    }

    private fun stored(vararg records: SubmissionRecord): List<RecordedSubmission> =
        records.map { RecordedSubmission.ofReceived(SubmissionRecordJson.encode(it))!! }
}
