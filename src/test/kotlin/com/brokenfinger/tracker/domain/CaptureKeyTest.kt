package com.brokenfinger.tracker.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Pure derivation — no mocks, no I/O (dev rules §3).
 *
 * Programmers issues no submission id (protocol doc §11) and `(lessonId, action, attempt)`
 * is not unique for `run`, because a run keeps the previous submit's attempt number
 * (design §5.1). The capture key is therefore the only dedup handle the writer has, and
 * the property it must hold is stability: a re-subscribe after a reconnect replays the
 * same terminal frame and must produce the same key in a *different process*.
 */
class CaptureKeyTest {
    private val finishFrame = """{"type":"finish"}"""

    @Test
    fun `the same lesson action and terminal frame always derive the same key`() {
        val first = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, terminalFrame = finishFrame)
        val second = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, terminalFrame = finishFrame)

        first shouldBe second
    }

    /**
     * The stability guarantee cannot be expressed by comparing two calls in one process —
     * a wall-clock or random ingredient would satisfy that and still break across a
     * restart. Pinning the digest is what actually forbids it. Oracle: an independent
     * `shasum -a 256` over `120804 US SUBMIT US {"type":"finish"}` (US = U+001F).
     */
    @Test
    fun `the key is pinned to an externally computed digest so a restart cannot change it`() {
        val key = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, terminalFrame = finishFrame)

        key.value shouldBe "7f4afc0c3bbc82c8"
    }

    @Test
    fun `the run of an identical frame is a different capture than the submit`() {
        val run = CaptureKey.of(lessonId = 120804, action = GradingAction.RUN, terminalFrame = finishFrame)

        run.value shouldBe "7f54caa9419b3808"
    }

    @Test
    fun `the same frame observed on another lesson derives another key`() {
        val here = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, terminalFrame = finishFrame)
        val there = CaptureKey.of(lessonId = 131528, action = GradingAction.SUBMIT, terminalFrame = finishFrame)

        here shouldNotBe there
    }

    @Test
    fun `a differing terminal frame derives another key`() {
        val pass = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, terminalFrame = finishFrame)
        val other = CaptureKey.of(
            lessonId = 120804,
            action = GradingAction.SUBMIT,
            terminalFrame = """{"type":"finish","msg":"실패 (시간 초과)"}""",
        )

        pass shouldNotBe other
    }

    @Test
    fun `the key is lowercase hex of a fixed width so it is safe in a file name`() {
        val key = CaptureKey.of(lessonId = 1, action = GradingAction.SUBMIT, terminalFrame = finishFrame)

        key.value.length shouldBe 16
        key.value.all { it in "0123456789abcdef" } shouldBe true
    }

    @Test
    fun `a blank terminal frame is rejected rather than defaulted`() {
        shouldThrow<IllegalArgumentException> {
            CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, terminalFrame = "   ")
        }
    }

    @Test
    fun `an empty key value is rejected`() {
        shouldThrow<IllegalArgumentException> { CaptureKey("") }
    }

    @Test
    fun `a key read back from storage is accepted leniently`() {
        CaptureKey.ofReceived("7f4afc0c3bbc82c8")?.value shouldBe "7f4afc0c3bbc82c8"
        CaptureKey.ofReceived(null).shouldBeNull()
        CaptureKey.ofReceived(" ").shouldBeNull()
    }
}
