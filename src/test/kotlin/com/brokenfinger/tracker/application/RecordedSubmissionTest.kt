package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.support.fixtures.aRecordLine
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RecordedSubmissionTest {
    @Test
    fun `reads the fields the attempt authority depends on`() {
        val record = RecordedSubmission.ofReceived(aRecordLine(lessonId = 131528, attempt = 3, language = "mysql"))!!

        record.lessonId shouldBe 131528
        record.action shouldBe GradingAction.SUBMIT
        record.attempt shouldBe 3
        record.language shouldBe "mysql"
    }

    @Test
    fun `keeps the original line verbatim so nothing is lost to our projection`() {
        val line = aRecordLine()

        RecordedSubmission.ofReceived(line)!!.line shouldBe line
    }

    @Test
    fun `ignores keys it does not know, so a wider record still parses`() {
        val line = aRecordLine(extra = ""","captureKey":"a3f1","tcSummary":{"total":16},"score":null""")

        RecordedSubmission.ofReceived(line)!!.attempt shouldBe 1
    }

    @Test
    fun `an unrecognised action keeps the record instead of discarding it`() {
        val record = RecordedSubmission.ofReceived(aRecordLine(action = "rejudge"))!!

        record.action.shouldBeNull()
        record.attempt shouldBe 1
    }

    @Test
    fun `a missing attempt reads as none rather than as attempt one`() {
        val line = """{"lessonId":120804,"action":"run"}"""

        RecordedSubmission.ofReceived(line)!!.attempt shouldBe 0
    }

    @Test
    fun `a truncated line yields nothing instead of throwing`() {
        RecordedSubmission.ofReceived("""{"lessonId":120804,"action":"sub""").shouldBeNull()
    }

    @Test
    fun `a line without a lesson id yields nothing — it cannot be attributed to a problem`() {
        RecordedSubmission.ofReceived("""{"action":"submit","attempt":2}""").shouldBeNull()
    }

    @Test
    fun `a blank line yields nothing`() {
        RecordedSubmission.ofReceived("   ").shouldBeNull()
    }

    @Test
    fun `a wrongly typed attempt yields nothing rather than a wrong number`() {
        RecordedSubmission.ofReceived("""{"lessonId":120804,"attempt":"two"}""").shouldBeNull()
    }
}
