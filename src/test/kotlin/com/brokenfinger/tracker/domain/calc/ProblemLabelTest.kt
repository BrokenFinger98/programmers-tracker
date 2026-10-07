package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Zero mocks. Catalog fields arrive late and unevenly, so each is taken from the newest record carrying it. */
class ProblemLabelTest {
    @Test
    fun `the newest record's fields win`() {
        val older = record(at = T0, title = "old", level = 1, part = "SELECT")
        val newer = record(at = T1, title = "new", level = 2, part = "JOIN")

        ProblemLabel.of(listOf(newer, older)) shouldBe ProblemLabel(120804, "new", 2, "JOIN")
    }

    @Test
    fun `an older record fills a field the newest lacks`() {
        val older = record(at = T0, title = "old", level = 1, part = "SELECT")
        val newer = record(at = T1, title = " ", level = null, part = "")

        ProblemLabel.of(listOf(older, newer)) shouldBe ProblemLabel(120804, "old", 1, "SELECT")
    }

    @Test
    fun `a field no record carries stays absent`() {
        val only = record(at = T0, title = "", level = null, part = null)

        ProblemLabel.of(listOf(only)) shouldBe ProblemLabel(120804, null, null, null)
    }

    /**
     * `RecordQuery.history()` is newest first, ties in reverse log order, so of two records that
     * share a timestamp the one handed in first is the later one — and it is the one `get_problem` shows.
     */
    @Test
    fun `of two records that share a timestamp, the one handed in first wins`() {
        val first = record(at = T0, title = "first")
        val second = record(at = T0, title = "second")

        ProblemLabel.of(listOf(first, second)).title shouldBe "first"
        ProblemLabel.of(listOf(second, first)).title shouldBe "second"
    }

    @Test
    fun `no records is refused`() {
        shouldThrow<IllegalArgumentException> { ProblemLabel.of(emptyList()) }
    }

    @Test
    fun `records of two problems are refused`() {
        shouldThrow<IllegalArgumentException> {
            ProblemLabel.of(listOf(record(at = T0, lessonId = 1), record(at = T1, lessonId = 2)))
        }
    }

    private fun record(
        at: String,
        title: String = "t",
        level: Int? = 1,
        part: String? = "SELECT",
        lessonId: Long = 120804,
    ) = aSubmissionRecord(ts = OffsetDateTime.parse(at), lessonId = lessonId, title = title, level = level, part = part)

    private companion object {
        const val T0 = "2026-10-07T10:00:00+09:00"
        const val T1 = "2026-10-07T10:00:05+09:00"
    }
}
