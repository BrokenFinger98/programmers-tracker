package com.brokenfinger.tracker.domain

import com.brokenfinger.tracker.support.fixtures.aSqlSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * `log/submissions.jsonl` is the authority for attempt numbering and the dedup index
 * ([[decisions/2026-08-05-write-serialization]]), so a record that does not survive a
 * round trip is a record we cannot re-read after a restart.
 */
class SubmissionRecordJsonTest {
    @Test
    fun `an algorithm record round-trips losslessly`() {
        val record = aSubmissionRecord()

        SubmissionRecordJson.decode(SubmissionRecordJson.encode(record)) shouldBe record
    }

    @Test
    fun `a database record round-trips with its structural nulls intact`() {
        val record = aSqlSubmissionRecord()

        val decoded = SubmissionRecordJson.decode(SubmissionRecordJson.encode(record))

        decoded shouldBe record
        decoded.score shouldBe null
        decoded.rating shouldBe null
        decoded.testcases.single().runTime shouldBe null
        decoded.testcases.single().memorySize shouldBe null
    }

    @Test
    fun `a structurally absent value is written as null and never as zero`() {
        val line = SubmissionRecordJson.encode(aSqlSubmissionRecord())

        line shouldContain "\"score\":null"
        line shouldContain "\"rating\":null"
        line shouldContain "\"runTime\":null"
        line shouldContain "\"memorySize\":null"
        line shouldNotContain "\"runTime\":0"
        line shouldNotContain "\"memorySize\":0"
    }

    @Test
    fun `an encoded record occupies exactly one JSONL line even when the error text is multiline`() {
        val compilerOutput = "Main.java:3: error: ';' expected\n        return a\n              ^"
        val record = aSubmissionRecord(errorText = compilerOutput)

        val line = SubmissionRecordJson.encode(record)

        line shouldNotContain "\n"
        SubmissionRecordJson.decode(line).errorText shouldBe record.errorText
    }

    /**
     * Lenient decoding (dev rules §4): a field added by a later version of the writer must
     * not make the whole line — and therefore the whole attempt — unreadable.
     */
    @Test
    fun `an unknown field does not fail the decode`() {
        val line = SubmissionRecordJson.encode(aSubmissionRecord())
        val extended = line.replaceFirst("{", """{"someFieldWeDoNotKnowYet":{"nested":[1,2]},""")

        SubmissionRecordJson.decode(extended) shouldBe aSubmissionRecord()
    }

    @Test
    fun `the wire keys and enum spellings follow design section 5 point 2`() {
        val line = SubmissionRecordJson.encode(aSubmissionRecord(verdict = Verdict.TIMEOUT))

        line shouldContain "\"action\":\"submit\""
        line shouldContain "\"outcome\":\"JUDGED\""
        line shouldContain "\"verdict\":\"TIMEOUT\""
        line shouldContain "\"captureKey\":\"7f4afc0c3bbc82c8\""
        line shouldContain "\"tcSummary\":{\"total\":1,\"passed\":1,\"failed\":0,\"complete\":true}"
        line shouldContain "\"ts\":\"2026-08-04T14:23:01+09:00\""
    }

    @Test
    fun `a run is spelled in lower case too`() {
        SubmissionRecordJson.encode(aSubmissionRecord(action = GradingAction.RUN)) shouldContain "\"action\":\"run\""
    }

    @Test
    fun `an unobserved grading keeps a null verdict through the round trip`() {
        val record = aSubmissionRecord(
            outcome = Outcome.INCOMPLETE,
            verdict = null,
            testcases = listOf(aTestcaseResult(passed = null, msg = null, runTime = null, memorySize = null)),
            tcSummary = TestcaseSummary.of(emptyList(), complete = false),
        )

        val decoded = SubmissionRecordJson.decode(SubmissionRecordJson.encode(record))

        decoded shouldBe record
        decoded.verdict shouldBe null
    }
}
