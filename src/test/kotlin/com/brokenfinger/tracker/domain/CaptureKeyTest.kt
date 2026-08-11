package com.brokenfinger.tracker.domain

import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
    fun `the same lesson action and frames always derive the same key`() {
        val first = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, frames = listOf(finishFrame))
        val second = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, frames = listOf(finishFrame))

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
        val key = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, frames = listOf(finishFrame))

        key.value shouldBe "7f4afc0c3bbc82c8"
    }

    @Test
    fun `the run of an identical frame is a different capture than the submit`() {
        val run = CaptureKey.of(lessonId = 120804, action = GradingAction.RUN, frames = listOf(finishFrame))

        run.value shouldBe "7f54caa9419b3808"
    }

    @Test
    fun `the same frame observed on another lesson derives another key`() {
        val here = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, frames = listOf(finishFrame))
        val there = CaptureKey.of(lessonId = 131528, action = GradingAction.SUBMIT, frames = listOf(finishFrame))

        here shouldNotBe there
    }

    @Test
    fun `a differing frame derives another key`() {
        val pass = CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, frames = listOf(finishFrame))
        val other = CaptureKey.of(
            lessonId = 120804,
            action = GradingAction.SUBMIT,
            frames = listOf("""{"type":"finish","msg":"failed (timeout)"}"""),
        )

        pass shouldNotBe other
    }

    @Test
    fun `the key is lowercase hex of a fixed width so it is safe in a file name`() {
        val key = CaptureKey.of(lessonId = 1, action = GradingAction.SUBMIT, frames = listOf(finishFrame))

        key.value.length shouldBe 16
        key.value.all { it in "0123456789abcdef" } shouldBe true
    }

    @Test
    fun `a grading with nothing but blank frames is rejected rather than defaulted`() {
        shouldThrow<IllegalArgumentException> {
            CaptureKey.of(lessonId = 120804, action = GradingAction.SUBMIT, frames = listOf("   "))
        }
    }

    /**
     * The defect this key exists to not have (#149). Measured on lesson 181947, 2026-08-11:
     * a passing submit derived the key of a WRONG submit from five days earlier, because an
     * algorithm submit terminates on `finish` and a `finish` carries nothing that varies —
     * so the key was a constant per problem and every submit after the first was dropped.
     *
     * The second grading here is the measured one with a single testcase timing changed,
     * which is the smallest difference two real submissions can have.
     */
    @Test
    fun `two gradings of one problem that end on the same frame are different captures`() {
        val first = FixtureLoader.rawFrames("algorithm-resubmit.jsonl")
        val second = first.map { it.replace("\"run_time\":\"82.73\"", "\"run_time\":\"91.04\"") }
        second shouldNotBe first
        first.last() shouldBe second.last()

        CaptureKey.of(181947, GradingAction.SUBMIT, first) shouldNotBe
            CaptureKey.of(181947, GradingAction.SUBMIT, second)
    }

    /** Why the terminal frame alone could never work — the measured frame, read back. */
    @Test
    fun `the frame an algorithm submit ends on carries nothing that varies`() {
        val finish = FixtureLoader.rawFrames("algorithm-resubmit.jsonl").last()

        finish shouldContain "\"type\":\"finish\""
        listOf("passed", "run_time", "userScore", "verdict", "score").forEach {
            withClue(it) { finish.contains(it) shouldBe false }
        }
    }

    /** Stability under replay: the raw log strips the line break, the socket does not. */
    @Test
    fun `a frame keyed with its line break derives the key of the frame without one`() {
        val frames = FixtureLoader.rawFrames("algorithm-resubmit.jsonl")

        CaptureKey.of(181947, GradingAction.SUBMIT, frames.map { "$it\n" }) shouldBe
            CaptureKey.of(181947, GradingAction.SUBMIT, frames)
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
