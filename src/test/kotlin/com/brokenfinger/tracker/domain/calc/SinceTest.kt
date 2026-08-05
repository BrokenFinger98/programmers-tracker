package com.brokenfinger.tracker.domain.calc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class SinceTest {
    @Test
    fun `reads a bare date as a calendar day`() {
        Since.from("2026-08-01").shouldBeInstanceOf<Since.Day>().date shouldBe LocalDate.of(2026, 8, 1)
    }

    @Test
    fun `reads an offset date-time as an instant`() {
        val since = Since.from("2026-08-01T09:00:00+09:00").shouldBeInstanceOf<Since.Instant>()

        since.at shouldBe OffsetDateTime.parse("2026-08-01T09:00:00+09:00")
    }

    @Test
    fun `ignores surrounding whitespace`() {
        Since.from("  2026-08-01  ") shouldBe Since.Day(LocalDate.of(2026, 8, 1))
    }

    @Test
    fun `a day includes the day itself and everything after it`() {
        val since = Since.Day(LocalDate.of(2026, 8, 1))

        since.includes(OffsetDateTime.parse("2026-08-01T00:00:00+09:00")).shouldBeTrue()
        since.includes(OffsetDateTime.parse("2026-08-01T23:59:59+09:00")).shouldBeTrue()
        since.includes(OffsetDateTime.parse("2026-08-02T00:00:00+09:00")).shouldBeTrue()
    }

    @Test
    fun `a day excludes the day before it`() {
        Since.Day(LocalDate.of(2026, 8, 1))
            .includes(OffsetDateTime.parse("2026-07-31T23:59:59+09:00"))
            .shouldBeFalse()
    }

    /**
     * The reason [Since.Day] exists. Read as UTC midnight this record would fall outside
     * the query, and a Korean learner would silently lose the first nine hours of the day
     * they asked about.
     */
    @Test
    fun `a day is judged in the offset the record carries, not in UTC`() {
        Since.Day(LocalDate.of(2026, 8, 1))
            .includes(OffsetDateTime.parse("2026-08-01T00:30:00+09:00"))
            .shouldBeTrue()
    }

    @Test
    fun `an instant includes itself and everything after it`() {
        val since = Since.Instant(OffsetDateTime.parse("2026-08-01T09:00:00+09:00"))

        since.includes(OffsetDateTime.parse("2026-08-01T09:00:00+09:00")).shouldBeTrue()
        since.includes(OffsetDateTime.parse("2026-08-01T09:00:01+09:00")).shouldBeTrue()
    }

    @Test
    fun `an instant excludes anything before it, across offsets`() {
        val since = Since.Instant(OffsetDateTime.parse("2026-08-01T09:00:00+09:00"))

        since.includes(OffsetDateTime.parse("2026-08-01T08:59:59+09:00")).shouldBeFalse()
        // The same wall clock in UTC is nine hours later, so it is not before the bound.
        since.includes(OffsetDateTime.parse("2026-08-01T09:00:00Z")).shouldBeTrue()
    }

    @Test
    fun `refuses a blank bound rather than widening the query to everything`() {
        shouldThrow<IllegalArgumentException> { Since.from("   ") }.message.shouldContain("2026-08-01")
    }

    @Test
    fun `refuses a bound it cannot parse`() {
        shouldThrow<IllegalArgumentException> { Since.from("last tuesday") }
        shouldThrow<IllegalArgumentException> { Since.from("2026-13-45") }
        shouldThrow<IllegalArgumentException> { Since.from("2026-08-01T25:00:00+09:00") }
    }

    @Test
    fun `refuses a date-time with no offset, because the instant would be a guess`() {
        shouldThrow<IllegalArgumentException> { Since.from("2026-08-01T09:00:00") }
    }
}
