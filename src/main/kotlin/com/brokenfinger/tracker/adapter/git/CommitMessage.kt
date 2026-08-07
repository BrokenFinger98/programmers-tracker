package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.UnknownReason

/**
 * The subject line of one submit commit (design §4.6).
 *
 * ```
 * [Lv2] <problem title> — WRONG (12/16, attempt 3)
 * [Lv2] <problem title> — PASS (16/16, attempt 4, 24m18s)
 * 120804 — WRONG (12/16, attempt 3)                 // what a record without catalog data reads as
 * ```
 *
 * The shape exists so that verdict and attempt count stay readable from `git log` alone,
 * without opening a record. The design's own examples carry a Korean title; they are spelled
 * with a placeholder here only because committed comments are English (dev rules §12).
 *
 * **Every part degrades rather than being invented.** Title and level come from the shipped
 * catalog (#59), which is a snapshot and does not know a problem published after it was
 * built. For those the bracket is dropped and the lesson id stands in for the name — the
 * same fallback, for the same reason, that `ProblemReadme` already applies to its heading.
 * An invented title would read exactly like a real one, which is the silent-wrong-data
 * outcome CLAUDE.md ranks worst.
 *
 * The title itself is data and is kept verbatim: a Korean problem name is what Programmers
 * called it, and dev rules §12 governs prose we write, not values we received.
 */
object CommitMessage {
    fun of(record: SubmissionRecord): String =
        "${levelOf(record)}${nameOf(record)} — ${verdictOf(record)} (${detailsOf(record)})"

    private fun levelOf(record: SubmissionRecord): String = record.level?.let { "[Lv$it] " }.orEmpty()

    private fun nameOf(record: SubmissionRecord): String = record.title.ifBlank { "${record.lessonId}" }

    /** An unjudged submit reports its outcome; borrowing a neighbouring verdict would invent one. */
    private fun verdictOf(record: SubmissionRecord): String = record.verdict?.name ?: record.outcome.name

    private fun detailsOf(record: SubmissionRecord): String =
        listOfNotNull(reasonOf(record), testcasesOf(record), "attempt ${record.attempt}", elapsedOf(record))
            .joinToString(", ")

    /**
     * Why an UNKNOWN is unknown, when measured (#74). The browser can be showing a cached
     * 100.0 scoreboard while this subject says UNKNOWN; without the reason the two read as a
     * capture bug. Unmeasured causes stay absent — the classifier already refuses to guess.
     */
    private fun reasonOf(record: SubmissionRecord): String? = UnknownReason.of(record.outcome, record.errorText)?.label

    /** A grading that reported no testcase at all — a compile error — carries no counts. */
    private fun testcasesOf(record: SubmissionRecord): String? {
        val summary = record.tcSummary
        if (summary.total == 0) return null
        val counted = "${summary.passed}/${summary.total}"
        return if (summary.complete) counted else "$counted partial"
    }

    /** Only a pass has a solving time worth reading — a wrong answer is not the end of one. */
    private fun elapsedOf(record: SubmissionRecord): String? {
        if (record.verdict != Verdict.PASS || record.elapsedSec < 0) return null
        val minutes = record.elapsedSec / 60
        val seconds = record.elapsedSec % 60
        if (minutes < 60) return "%dm%02ds".format(minutes, seconds)
        return "%dh%02dm%02ds".format(minutes / 60, minutes % 60, seconds)
    }
}
