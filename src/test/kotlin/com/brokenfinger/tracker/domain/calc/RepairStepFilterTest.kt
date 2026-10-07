package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.support.fixtures.aCodedGrading
import com.brokenfinger.tracker.support.fixtures.aRun
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RepairStepFilterTest {
    private val select = ProblemLabel(lessonId = 1, title = "a", level = 1, part = "SELECT")
    private val join = ProblemLabel(lessonId = 2, title = "b", level = 2, part = "JOIN")
    private val unlabelled = ProblemLabel(lessonId = 3, title = null, level = null, part = null)

    /** D8: the correction is the event, so the order is by when it was recorded. */
    @Test
    fun `answers newest first, by when the correction was recorded`() {
        val older = stepOf(select, toAt = "2026-10-02T10:00:00+09:00")
        val newer = stepOf(join, toAt = "2026-10-03T10:00:00+09:00")

        filter().applied(listOf(older, newer)) shouldContainExactly listOf(newer, older)
    }

    @Test
    fun `since bounds the correction's time`() {
        val before = stepOf(select, toAt = "2026-09-30T23:00:00+09:00")
        val after = stepOf(select, toAt = "2026-10-01T09:00:00+09:00")

        filter(since = Since.Day(LocalDate.of(2026, 10, 1))).applied(listOf(before, after)) shouldContainExactly
            listOf(after)
    }

    @Test
    fun `language matches case-insensitively and in full`() {
        val java = stepOf(select, toAt = "2026-10-02T10:00:00+09:00", language = "java")
        val javascript = stepOf(select, toAt = "2026-10-02T11:00:00+09:00", language = "javascript")

        filter(language = " JAVA ").applied(listOf(java, javascript)) shouldContainExactly listOf(java)
    }

    @Test
    fun `part matches case-insensitively, and a step with no recorded part is left out`() {
        val selected = stepOf(select, toAt = "2026-10-02T10:00:00+09:00")
        val none = stepOf(unlabelled, toAt = "2026-10-02T11:00:00+09:00")

        val joined = stepOf(join, toAt = "2026-10-02T12:00:00+09:00")

        filter(part = "select").applied(listOf(selected, none, joined)) shouldContainExactly listOf(selected)
    }

    @Test
    fun `limit keeps the newest`() {
        val steps = (1..3).map { stepOf(select, toAt = "2026-10-0${it}T10:00:00+09:00") }

        filter(limit = 2).applied(steps) shouldContainExactly listOf(steps[2], steps[1])
    }

    @Test
    fun `no argument is the whole list`() {
        val steps = listOf(stepOf(unlabelled, toAt = "2026-10-02T10:00:00+09:00"))

        filter().applied(steps) shouldContainExactly steps
    }

    /** Dev rules §4: a filter is a value we create, so an argument it cannot honour is refused. */
    @Test
    fun `a limit that is not positive is refused`() {
        shouldThrow<IllegalArgumentException> { filter(limit = 0) }
        shouldThrow<IllegalArgumentException> { filter(limit = -1) }
    }

    @Test
    fun `a blank language is refused`() {
        shouldThrow<IllegalArgumentException> { filter(language = " ") }
    }

    @Test
    fun `a blank part is refused`() {
        shouldThrow<IllegalArgumentException> { filter(part = "") }
    }

    private fun filter(since: Since? = null, language: String? = null, part: String? = null, limit: Int? = null) =
        RepairStepFilter(since, language, part, limit)

    private fun stepOf(label: ProblemLabel, toAt: String, language: String = "java"): LabelledStep {
        val from = aCodedGrading(aRun(at = "2026-09-01T00:00:00+09:00", lessonId = label.lessonId, language = language))
        val to = aCodedGrading(aRun(at = toAt, lessonId = label.lessonId, language = language))
        return LabelledStep(label, Transition(from, to, diff = "d", noDiff = null))
    }
}
