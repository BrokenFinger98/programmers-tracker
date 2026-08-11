package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * How well a problem was held, and therefore how long before it is worth re-solving.
 *
 * Four bands rather than a number: the inputs justify an ordering, not a decimal, and a
 * confidence of `0.734` would claim a precision nothing here measures.
 */
enum class Confidence(val intervalDays: Long) {
    HIGH(60),
    MEDIUM(21),
    LOW(7),
    SHAKY(3),
    ;

    /** The spelling used on the wire, which is also what the tool schema enumerates. */
    fun wireName(): String = name.lowercase()
}

/**
 * One problem the queue says is worth re-solving.
 *
 * **The inputs travel with the verdict on purpose.** The server schedules; it does not
 * diagnose (CLAUDE.md's forbidden list, resolved in
 * [[decisions/2026-08-10-scheduling-is-not-diagnosis]]). A reader that disagrees with
 * [confidence] can see every fact that produced it and say so — which is only possible if the
 * facts are here rather than consumed.
 */
data class ReviewItem(
    val lessonId: Long,
    val title: String,
    /**
     * The language the pass was written in, and half of this item's identity.
     *
     * A problem can appear twice — a pass in Kotlin says nothing about whether the learner can
     * write it in Java, and someone practising a second language because a company does not
     * offer their first would otherwise be told they were done with a problem they have never
     * solved in it (#173).
     */
    val language: String,
    val level: Int?,
    val passedAt: OffsetDateTime,
    /** Submits it took to pass, counted from the previous pass rather than from the beginning. */
    val attempts: Int,
    /** Null means no extension was watching — never "no help was taken". */
    val sawQuestions: Boolean?,
    /** Reported, never scored: calibrating it needs a per-level distribution we do not have. */
    val focusedSec: Long?,
    val confidence: Confidence,
    val dueAt: LocalDate,
    /** Zero on the day it falls due; negative would not be in the queue at all. */
    val overdueDays: Long,
)

/**
 * The spaced-repetition schedule over recorded passes (design §6.4).
 *
 * Programmers knows only *that* a problem was solved. The records know **how** — how many
 * submits it took, whether the questions tab was opened while stuck — and that is what makes a
 * schedule possible without any similarity search.
 *
 * Pure by construction (dev rules §3): a snapshot and a date in, items out. Nothing here reads
 * a file, so the same computation serves a live query and a re-analysis of old records without
 * two implementations drifting apart.
 *
 * **A pass is per language.** Grouping is by `(lesson, language)`, so a problem passed in two
 * languages is two items on two schedules. The counter-argument is real — solving it once
 * teaches the algorithm and the second language is largely syntax — but weighing that is a
 * claim about the learner, and the server schedules rather than diagnoses
 * ([[decisions/2026-08-10-scheduling-is-not-diagnosis]]). Both items carry the facts that
 * scheduled them, and the reader decides.
 *
 * **Scope falls out rather than being enforced.** Only problems with a recorded pass appear,
 * which is exactly "problems solved from now on" — one solved before this tool existed has no
 * record at all, so no date cutoff is needed and none is applied.
 */
object ReviewQueue {
    /**
     * [now] is an instant, and each item's "today" is read from **the offset its own pass was
     * recorded in**. A learner in Seoul running the server in a container is nine hours apart
     * from it, and a global timezone setting would be a second place for that to be wrong; the
     * record already carries the only offset that means anything here.
     */
    fun due(history: List<SubmissionRecord>, now: OffsetDateTime, limit: Int? = null): List<ReviewItem> {
        val items = history.groupBy { it.lessonId to it.language }.values
            .mapNotNull { itemOf(it, now) }
            .filter { it.overdueDays >= 0 }
            .sortedWith(MOST_OVERDUE_FIRST)
        return limit?.let { items.take(it) } ?: items
    }

    private fun itemOf(problem: List<SubmissionRecord>, now: OffsetDateTime): ReviewItem? {
        val ordered = problem.sortedBy { it.ts }
        val passIndex = ordered.indexOfLast { it.passed() }
        if (passIndex < 0) return null
        return itemOf(ordered[passIndex], attemptsFor(ordered, passIndex), now)
    }

    private fun itemOf(pass: SubmissionRecord, attempts: Int, now: OffsetDateTime): ReviewItem {
        val confidence = confidenceOf(attempts, pass.sensor?.sawQuestions)
        val dueAt = pass.ts.toLocalDate().plusDays(confidence.intervalDays)
        val today = now.withOffsetSameInstant(pass.ts.offset).toLocalDate()
        return ReviewItem(
            lessonId = pass.lessonId,
            title = pass.title,
            language = pass.language,
            level = pass.level,
            passedAt = pass.ts,
            attempts = attempts,
            sawQuestions = pass.sensor?.sawQuestions,
            focusedSec = pass.sensor?.focusedSec,
            confidence = confidence,
            dueAt = dueAt,
            overdueDays = ChronoUnit.DAYS.between(dueAt, today),
        )
    }

    /**
     * Submits between the previous pass and this one.
     *
     * Counting the problem's whole history would make a second pass look shakier than the
     * first, which is the opposite of what passing again means — the interval has to lengthen
     * on a re-solve or the queue never lets anything go.
     */
    private fun attemptsFor(ordered: List<SubmissionRecord>, passIndex: Int): Int {
        val previousPass = ordered.subList(0, passIndex).indexOfLast { it.passed() }
        return ordered.subList(previousPass + 1, passIndex + 1).count { it.action == GradingAction.SUBMIT }
    }

    private fun confidenceOf(attempts: Int, sawQuestions: Boolean?): Confidence {
        val band = bandFor(attemptPoints(attempts) + helpPoints(sawQuestions))
        if (sawQuestions != null) return band
        return unobserved(band)
    }

    /**
     * **Absence must never buy confidence.** A record written with no extension watching says
     * nothing about whether help was taken, and reading that as "none was" is the single error
     * direction that pushes a shaky problem two months out. It cannot reach the longest
     * interval; everything below it is already conservative.
     */
    private fun unobserved(band: Confidence): Confidence {
        if (band != Confidence.HIGH) return band
        return Confidence.MEDIUM
    }

    // Chosen, not measured — the two anchors design §6.4 states are the only calibration that
    // exists, and this reproduces both. See the ADR's accepted costs before tuning them.
    private fun attemptPoints(attempts: Int): Int = when {
        attempts <= 1 -> 0
        attempts == 2 -> 1
        attempts <= 4 -> 2
        else -> 3
    }

    private fun helpPoints(sawQuestions: Boolean?): Int = when (sawQuestions) {
        true -> 2
        else -> 0
    }

    private fun bandFor(points: Int): Confidence = when {
        points == 0 -> Confidence.HIGH
        points <= 2 -> Confidence.MEDIUM
        points <= 4 -> Confidence.LOW
        else -> Confidence.SHAKY
    }

    private fun SubmissionRecord.passed(): Boolean = action == GradingAction.SUBMIT && verdict == Verdict.PASS

    // Most overdue first, then the least confident, then by problem and language so the order
    // is total — a queue that reshuffles between identical calls is one nobody can work through.
    // Language is part of the tie-break because one problem can now produce two items.
    private val MOST_OVERDUE_FIRST = compareByDescending<ReviewItem> { it.overdueDays }
        .thenByDescending { it.confidence.ordinal }
        .thenBy { it.lessonId }
        .thenBy { it.language }
}
