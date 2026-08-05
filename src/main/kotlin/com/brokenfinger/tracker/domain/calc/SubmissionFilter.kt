package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The lower bound of a history query (dev rules §3 — a pure calculator, no clock, no I/O).
 *
 * Two spellings, because they answer two different questions and collapsing them would
 * make one of the answers wrong. "Since 2026-08-01" is a question about calendar days and
 * is answered in the offset the record itself carries; a full offset date-time is a
 * question about an instant. Interpreting a bare date as UTC midnight would silently drop
 * the first nine hours of a Korean day.
 */
sealed interface Since {
    fun includes(ts: OffsetDateTime): Boolean

    /** A calendar day, compared against the record's own local date. */
    data class Day(val date: LocalDate) : Since {
        override fun includes(ts: OffsetDateTime): Boolean = !ts.toLocalDate().isBefore(date)
    }

    /** An exact instant, offset included. */
    data class Instant(val at: OffsetDateTime) : Since {
        override fun includes(ts: OffsetDateTime): Boolean = !ts.isBefore(at)
    }

    companion object {
        /**
         * Strict (dev rules §4) — this is a value a caller *created*, not one we received
         * from the protocol, so a bound we cannot parse must fail loudly rather than
         * quietly widening the query to everything.
         */
        fun from(raw: String): Since {
            val text = raw.trim()
            require(text.isNotEmpty()) { FORMAT }
            return runCatching { parse(text) }.getOrElse { throw IllegalArgumentException(FORMAT) }
        }

        // A date-time is distinguishable from a date by the ISO-8601 date/time separator.
        private fun parse(text: String): Since =
            if (text.contains('T')) Instant(OffsetDateTime.parse(text)) else Day(LocalDate.parse(text))

        const val FORMAT: String =
            "since must be a date (2026-08-01) or an offset date-time (2026-08-01T09:00:00+09:00)"
    }
}

/**
 * Selects submissions out of an in-memory snapshot. Every argument is optional and an
 * absent one is not a filter, so a query with no arguments is the whole history rather
 * than an empty one.
 */
object SubmissionFilter {
    fun matching(records: List<SubmissionRecord>, since: Since?, verdict: Verdict?): List<SubmissionRecord> =
        records.filter { since == null || since.includes(it.ts) }
            .filter { verdict == null || it.verdict == verdict }

    fun ofProblem(records: List<SubmissionRecord>, lessonId: Long): List<SubmissionRecord> =
        records.filter { it.lessonId == lessonId }
}
