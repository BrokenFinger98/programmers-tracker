package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aRawSessionId
import com.brokenfinger.tracker.support.fixtures.aSessionOf
import com.brokenfinger.tracker.support.fixtures.aSettledCapture
import com.brokenfinger.tracker.support.fixtures.aTerminalFrame
import com.brokenfinger.tracker.support.fixtures.aTruncatedStream
import com.brokenfinger.tracker.support.fixtures.anAssembledSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class SettledCaptureTest {
    @Test
    fun `the capture key is derived from the frame that ended the stream`() {
        val capture = aSettledCapture()

        capture.captureKey() shouldBe capture.captureKey()
    }

    @Test
    fun `a different terminal frame is a different grading`() {
        val pass = aSettledCapture(terminalFrame = aTerminalFrame("algorithm-pass.jsonl"))
        val wrong = aSettledCapture(terminalFrame = aTerminalFrame("algorithm-wrong.jsonl"))

        pass.captureKey() shouldNotBe wrong.captureKey()
    }

    @Test
    fun `the same grading on another problem is another key`() {
        val here = aSettledCapture(lessonId = 120804)
        val there = aSettledCapture(lessonId = 131528)

        here.captureKey() shouldNotBe there.captureKey()
    }

    @Test
    fun `a session that never terminated falls back to the raw session id, which replay repeats`() {
        val incomplete = { aSettledCapture(session = truncated(), terminalFrame = null) }

        incomplete().captureKey() shouldBe incomplete().captureKey()
    }

    @Test
    fun `two unterminated sessions of the same problem stay distinct`() {
        val first = anUnterminatedCapture("a.jsonl")
        val second = anUnterminatedCapture("b.jsonl")

        first.captureKey() shouldNotBe second.captureKey()
    }

    @Test
    fun `a blank terminal frame falls back too, instead of throwing the record away`() {
        aSettledCapture(terminalFrame = "  ").captureKey() shouldBe
            aSettledCapture(session = truncated(), terminalFrame = null).captureKey()
    }

    @Test
    fun `a stream that announced no action is rejected rather than filed under a guess`() {
        val actionless = aSettledCapture(session = aSessionOf(emptyList()))

        shouldThrow<IllegalArgumentException> { actionless.captureKey() }
    }

    @Test
    fun `the record carries the settled verdict and its testcases`() {
        val capture = aSettledCapture()

        val record = capture.toRecord(now(), attempt = 2, key = capture.captureKey(), rawPath = "raw")

        record.outcome shouldBe Outcome.JUDGED
        record.verdict shouldBe Verdict.PASS
        record.testcases shouldBe capture.session.testcases
    }

    @Test
    fun `the testcase summary states whether the observed set was the whole grading`() {
        val capture = aSettledCapture()

        val record = capture.toRecord(now(), attempt = 1, key = capture.captureKey(), rawPath = "raw")

        record.tcSummary.total shouldBe capture.session.testcases.size
        record.tcSummary.complete shouldBe capture.session.testcasesComplete
    }

    @Test
    fun `the code is pending until an attachment succeeds, and the verdict does not wait for it`() {
        val record = aSettledCapture().let { it.toRecord(now(), 1, it.captureKey(), "raw") }

        record.codePending shouldBe true
        record.codePath shouldBe null
        record.isCodeAttached() shouldBe false
    }

    @Test
    fun `identity and timing come from the capture, not from a guess`() {
        val capture = aSettledCapture(elapsedSec = 42)

        val record = capture.toRecord(now(), attempt = 3, key = capture.captureKey(), rawPath = "somewhere")

        record.lessonId shouldBe 120804
        record.language shouldBe "java"
        record.action shouldBe GradingAction.SUBMIT
        record.attempt shouldBe 3
        record.elapsedSec shouldBe 42
        record.ts shouldBe now()
        record.rawPath shouldBe "somewhere"
    }

    @Test
    fun `run-path error text survives onto the record`() {
        val capture = aSettledCapture(session = anAssembledSession("algorithm-run-error.jsonl"))

        val record = capture.toRecord(now(), 0, capture.captureKey(), "raw")

        record.errorText shouldBe capture.session.errorText
    }

    @Test
    fun `only a submit that owns a number moves its frames into an attempt file`() {
        aSettledCapture().movesRaw(attempt = 1) shouldBe true
        aSettledCapture().movesRaw(attempt = AttemptAuthority.NONE) shouldBe false
        aSettledCapture(session = anAssembledSession("algorithm-run-pass.jsonl")).movesRaw(attempt = 1) shouldBe false
    }

    // A stream cut before any terminal frame — the shape a timeout or a disconnect leaves.
    private fun truncated() = aSessionOf(aTruncatedStream())

    private fun anUnterminatedCapture(rawName: String) =
        aSettledCapture(session = truncated(), terminalFrame = null, rawSessionId = aRawSessionId(rawName))

    private fun now(): OffsetDateTime = OffsetDateTime.parse("2026-08-04T14:23:01+09:00")
}
