package com.brokenfinger.tracker.domain

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * One line of `log/submissions.jsonl` — the schema of design §5.2.
 *
 * Every field the SQL path never sends is nullable, and the null is load-bearing: a
 * database grading has no score, no rating and no per-testcase timing at all
 * (protocol doc §6). Writing zero instead would make those gradings drag every average
 * down, silently — the outcome CLAUDE.md names as the worst possible one.
 *
 * `lessonId` is a plain [Long] rather than [LessonId] because this is the serialized form:
 * the number is what lands in the JSONL, and wrapping it would put a value class in the
 * schema that every reader of a stored record would then have to unwrap.
 */
@Serializable
data class SubmissionRecord(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val ts: OffsetDateTime,
    val lessonId: Long,
    val title: String,
    /** Catalog metadata. Null for a problem the cached catalog has not seen yet. */
    val level: Int? = null,
    val part: String? = null,
    val acceptanceRate: Int? = null,
    /** Empty, never null, for an untagged problem — aggregation counts those separately (design §5.3). */
    val tags: List<String> = emptyList(),
    val language: String,
    val action: GradingAction,
    val attempt: Int,
    val elapsedSec: Long,
    /** Null for the first submission of a problem — there is no previous one to measure from. */
    val sincePrevSec: Long? = null,
    val hintLevel: Int = 0,
    val captureKey: CaptureKey,
    val outcome: Outcome,
    /** Meaningful only when [outcome] is [Outcome.JUDGED] (design §3.3). */
    val verdict: Verdict? = null,
    /** Null for every database grading — the SQL path reports no score (protocol doc §6). */
    val score: Score? = null,
    val testcases: List<TestcaseResult> = emptyList(),
    val tcSummary: TestcaseSummary,
    /** Null for every database grading — the SQL path reports no rating (protocol doc §6). */
    val rating: RatingChange? = null,
    /**
     * Where the original frames live inside the record repository, which is what keeps
     * re-analysis possible (dev rules §2.4).
     *
     * **Null for a run.** A run creates no attempt file (design §5.1), so its frames have no
     * home inside the repository — they are kept outside it, under the tool's own state
     * directory. Null says that plainly; the bare session id this used to carry resolved to
     * nothing and read like a path that had been lost (#99).
     */
    val rawPath: String?,
    /**
     * What the browser saw, when a sensor was watching (#120) — focused time and whether the
     * questions tab was opened. **Null is the normal absence**: no extension, a watch started
     * by hand, or an extension loaded after the problem was already open.
     *
     * Its own object because it comes from a different source than everything above it: the
     * rest of this record came off the wire from Programmers, this came from a browser
     * extension, and a reader weighing the two should be able to see which is which.
     */
    val sensor: SensorObservation? = null,
    val codePath: String? = null,
    /** True while the verdict is durable but the code fetch has not succeeded yet (design §5.2). */
    val codePending: Boolean = false,
    val diffFromPrev: String? = null,
    /** Full compiler output or stack trace, available only from the run path (design §5.1). */
    val errorText: String? = null,
) {
    fun isJudged(): Boolean = outcome == Outcome.JUDGED

    fun isCodeAttached(): Boolean = !codePending && codePath != null
}

/**
 * What the judge scored, kept as the strings it reported. Parsing them to a number here
 * would decide a precision the judge never promised.
 */
@Serializable
data class Score(val user: String, val perfect: String) {
    companion object {
        /** Lenient (dev rules §4). A half-reported score is no score — never a zero. */
        fun ofReceived(user: String?, perfect: String?): Score? {
            if (user == null || perfect == null) return null
            return Score(user, perfect)
        }
    }
}

/**
 * Counts over the testcases actually stored on this record.
 *
 * [complete] is the separate question of whether that set was the whole grading: false
 * means fewer testcases arrived than the stream announced, so a partially observed
 * grading can never masquerade as a full one (design §5.2).
 */
@Serializable
data class TestcaseSummary(val total: Int, val passed: Int, val failed: Int, val complete: Boolean) {
    companion object {
        fun of(testcases: List<TestcaseResult>, complete: Boolean): TestcaseSummary {
            val failed = testcases.count { it.hasFailed() }
            return TestcaseSummary(testcases.size, testcases.size - failed, failed, complete)
        }
    }
}

/** Rating before and after the grading. Absent for every database grading (protocol doc §6). */
@Serializable
data class RatingChange(val old: Int, val new: Int, val changed: Boolean) {
    companion object {
        fun of(old: Int, new: Int): RatingChange = RatingChange(old, new, old != new)

        /** Lenient (dev rules §4). A half-reported rating is no rating — never a zero. */
        fun ofReceived(old: Int?, new: Int?): RatingChange? {
            if (old == null || new == null) return null
            return of(old, new)
        }
    }
}
