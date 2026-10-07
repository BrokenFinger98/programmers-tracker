package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.support.fixtures.aKeptCode
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSubmit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks. The late-attachment rule of spec 2026-10-07 §4.2: code fetched after the next grading
 * of the problem was recorded may be that grading's code, and the reader must be told.
 */
class CodeTimelineTest {
    @Test
    fun `orders one problem's gradings by time and attaches each one's kept code`() {
        val later = aRun(at = "2026-10-07T10:00:05+09:00")
        val earlier = aRun(at = "2026-10-07T10:00:00+09:00")

        val timeline = CodeTimeline.of(listOf(later, earlier), mapOf(earlier.recordId() to aKeptCode("a")))

        timeline.map { it.record } shouldContainExactly listOf(earlier, later)
        timeline.map { it.code?.text } shouldContainExactly listOf("a", null)
    }

    @Test
    fun `code fetched after the next grading was recorded is late`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:02+09:00")
        val codes = mapOf(first.recordId() to aKeptCode("a", fetchedAt = "2026-10-07T10:00:03+09:00"))

        CodeTimeline.of(listOf(first, second), codes).first().late shouldBe true
    }

    /** Measured 2026-10-07 on lesson 59036: 0.27–0.38 s after the record, well before the next. */
    @Test
    fun `code fetched before the next grading was recorded is not late`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:02+09:00")
        val codes = mapOf(first.recordId() to aKeptCode("a", fetchedAt = "2026-10-07T10:00:00.38+09:00"))

        CodeTimeline.of(listOf(first, second), codes).first().late shouldBe false
    }

    /** The settled rule names the problem's next record, whatever its language. */
    @Test
    fun `the next grading is the problem's next, in any language`() {
        val java = aRun(at = "2026-10-07T10:00:00+09:00", language = "java")
        val kotlin = aRun(at = "2026-10-07T10:00:02+09:00", language = "kotlin")
        val codes = mapOf(java.recordId() to aKeptCode("a", fetchedAt = "2026-10-07T10:00:03+09:00"))

        CodeTimeline.of(listOf(java, kotlin), codes).first().late shouldBe true
    }

    @Test
    fun `the last grading has nothing after it and is never late`() {
        val only = aRun(at = "2026-10-07T10:00:00+09:00")
        val codes = mapOf(only.recordId() to aKeptCode("a", fetchedAt = "2026-10-08T00:00:00+09:00"))

        CodeTimeline.of(listOf(only), codes).single().late shouldBe false
    }

    /** An attempt file records no fetch time, so there is nothing to check (D6). */
    @Test
    fun `code with no fetch time is never late`() {
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val next = aRun(at = "2026-10-07T10:00:02+09:00")

        CodeTimeline.of(listOf(submit, next), mapOf(submit.recordId() to aKeptCode("a"))).first().late shouldBe false
    }

    @Test
    fun `refuses the gradings of two problems`() {
        shouldThrow<IllegalArgumentException> {
            CodeTimeline.of(
                listOf(
                    aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1),
                    aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2),
                ),
                emptyMap(),
            )
        }
    }
}
