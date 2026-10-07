package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.SubmissionRecord

/**
 * What a tally may be grouped by. Each constant owns its own key and label because the
 * alternative — one `when` per question — puts the same three-way branch in two places
 * and lets them drift.
 */
enum class TallyGroup {
    VERDICT {
        // Null, not "UNKNOWN": a grading whose verdict was never resolved must not be
        // counted as one that was. Outcome.UNKNOWN is a different statement entirely.
        override fun keyOf(record: SubmissionRecord): String? = record.verdict?.name
    },
    LANGUAGE {
        override fun keyOf(record: SubmissionRecord): String? = record.language.takeIf { it.isNotBlank() }
    },
    PROBLEM {
        override fun keyOf(record: SubmissionRecord): String = record.lessonId.toString()

        override fun labelOf(record: SubmissionRecord): String? = record.title.takeIf { it.isNotBlank() }
    },
    PART {
        override fun keyOf(record: SubmissionRecord): String? = record.part?.takeIf { it.isNotBlank() }

        override fun countsProblems(): Boolean = true
    },
    LEVEL {
        override fun keyOf(record: SubmissionRecord): String? = record.level?.toString()

        override fun countsProblems(): Boolean = true
    },
    ;

    abstract fun keyOf(record: SubmissionRecord): String?

    /** A human-readable name for the key, when the key alone is a bare identifier. */
    open fun labelOf(record: SubmissionRecord): String? = null

    /**
     * Whether a bucket of this grouping also counts its problems ([ProblemProgress]). Only for
     * properties of the problem itself: on a verdict bucket "passed" would mean nothing.
     */
    open fun countsProblems(): Boolean = false

    /**
     * The bucket key of each record of [records]. For a grouping that counts problems the key is a
     * property of the problem, not of the record: the newest record carrying a value wins (the rule
     * `RecordQuery.problem()` uses), so a problem whose early records were captured before the
     * catalog knew it is still one bucket. A problem with no value anywhere has a null key.
     */
    fun bucketKeyOf(records: List<SubmissionRecord>): (SubmissionRecord) -> String? {
        if (!countsProblems()) return ::keyOf
        val keys = records.groupBy { it.lessonId }.mapValues { (_, grouped) -> newestKey(grouped) }
        return { keys[it.lessonId] }
    }

    private fun newestKey(grouped: List<SubmissionRecord>): String? = ProblemLabel.newestCarrying(grouped, ::keyOf)

    /** The spelling used on the wire, which is also what the tool schema enumerates. */
    fun wireName(): String = name.lowercase()

    companion object {
        /** Strict (dev rules §4) — an argument we cannot honour is refused, never defaulted. */
        fun from(raw: String): TallyGroup = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("groupBy must be one of ${wireNames()}")

        fun wireNames(): List<String> = entries.map { it.wireName() }
    }
}

/**
 * One bucket. **A missing [key] means the grouping value was never recorded** — it is not
 * a bucket named "unknown", because a placeholder that reads like a measurement is the
 * failure this project has already shipped once
 * ([[concepts/assumption-vs-measurement]]).
 *
 * [progress] is present only for groupings that count problems.
 *
 * Every field is required, and the type carries no serialization of its own. Both are
 * deliberate: this is what the calculator concluded, not what any transport sends, and the
 * JSON shape the MCP tools answer with is assembled in the adapter that owns it.
 */
data class TallyBucket(val key: String?, val label: String?, val count: Int, val progress: ProblemProgress?)

/**
 * Counts submissions per bucket, and does nothing else (dev rules §3).
 *
 * It draws no conclusion — no "weakest tag", no "needs review". The constitution puts
 * interpretation on the AI reading the numbers, and a calculator that ranked or judged
 * here would be the rule-based analyzer the server is forbidden to contain.
 *
 * **Submissions, so runs are dropped here** rather than by the caller (#235), through
 * [SubmissionRecord.isSubmission] so the rule is written once. Every other calculator already
 * tested the action — `ReviewQueue`, `SlowPasses`, the tag map — and this
 * one counted whatever `RecordQuery.history()` handed it, which is runs and submits both. On
 * the owner's own repository that made `stats(groupBy=problem)` answer 15 where
 * `list_problems` called the same problem 8 attempts, and put 7 compile errors into a verdict
 * tally where every one of them came from pressing Run while writing code. *A run is not an
 * attempt* is design §5.1's rule and `ProblemReadme` has always obeyed it; the numbers a
 * reader draws conclusions from must obey it too.
 *
 * Runs stay readable through `submissions`, which says it returns every recorded run and
 * submit and does.
 */
object SubmissionTally {
    /**
     * Submits only, filtered first (#235). [ProblemProgress] is computed on its own path over all
     * records, runs included, and joined into the part and level buckets here by key; the other
     * groupings get no progress.
     */
    fun of(records: List<SubmissionRecord>, group: TallyGroup): List<TallyBucket> {
        val keyOf = group.bucketKeyOf(records)
        val progress = ProblemProgress.perBucket(records, group)
        val buckets = records.filter { it.isSubmission() }
            .groupBy(keyOf)
            .map { (key, grouped) -> TallyBucket(key, group.labelOf(grouped.first()), grouped.size, progress[key]) }
        return ordered(buckets)
    }

    // Deterministic so a client can cache the answer and diff two of them: biggest bucket
    // first, ties broken by key.
    //
    // The keyless bucket sorts last **whatever its size**, ahead of the count. It is not a
    // bucket competing with the others but the count of what we failed to observe, and a
    // reader who takes the first entry as the headline must never be handed "nothing" as
    // the most common verdict.
    private fun ordered(buckets: List<TallyBucket>): List<TallyBucket> = buckets.sortedWith(
        compareBy<TallyBucket> { it.key == null }
            .thenByDescending { it.count }
            .thenBy(nullsLast()) { it.key },
    )
}
