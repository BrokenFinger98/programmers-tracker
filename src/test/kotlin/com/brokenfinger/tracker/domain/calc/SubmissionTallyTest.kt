package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class SubmissionTallyTest {
    @Test
    fun `counts by verdict`() {
        val records = listOf(
            aSubmissionRecord(verdict = Verdict.PASS),
            aSubmissionRecord(verdict = Verdict.WRONG),
            aSubmissionRecord(verdict = Verdict.WRONG),
        )

        val buckets = SubmissionTally.of(records, TallyGroup.VERDICT)

        buckets.sumOf { it.count } shouldBe 3
        buckets.shouldContainExactly(TallyBucket("WRONG", null, 2, null), TallyBucket("PASS", null, 1, null))
    }

    /**
     * The distinction the whole design turns on: a grading we never resolved is counted,
     * and counted as having no verdict — not folded into a bucket named after one
     * ([[concepts/assumption-vs-measurement]]).
     */
    @Test
    fun `an unresolved grading gets no key rather than a placeholder one`() {
        val records = listOf(
            aSubmissionRecord(verdict = Verdict.PASS),
            aSubmissionRecord(outcome = Outcome.INCOMPLETE, verdict = null),
            aSubmissionRecord(outcome = Outcome.UNKNOWN, verdict = null),
        )

        val buckets = SubmissionTally.of(records, TallyGroup.VERDICT)

        buckets.shouldContainExactly(TallyBucket("PASS", null, 1, null), TallyBucket(null, null, 2, null))
        buckets.last().key.shouldBeNull()
    }

    /**
     * A run is not a submission (design §5.1), and this is the only calculator that had to be
     * told. `ReviewQueue`, `SlowPasses` and the tag map all test the action; this one counted
     * whatever `RecordQuery.history()` handed it, so on the owner's own repository
     * `stats(groupBy=problem)` answered 15 for a problem `list_problems` called 8 attempts (#235).
     */
    @Test
    fun `a run is not counted, because a run is not a submission`() {
        val records = listOf(
            aSubmissionRecord(action = GradingAction.SUBMIT, verdict = Verdict.PASS),
            aSubmissionRecord(action = GradingAction.RUN, verdict = Verdict.COMPILE_ERROR),
            aSubmissionRecord(action = GradingAction.RUN, verdict = Verdict.PASS),
        )

        SubmissionTally.of(records, TallyGroup.VERDICT).shouldContainExactly(TallyBucket("PASS", null, 1, null))
    }

    /**
     * The shape the defect actually took: every compile error in the live tally came from
     * pressing Run while writing code, and an AI reading it saw a learner who cannot compile.
     */
    @Test
    fun `a verdict only runs ever produced makes no bucket at all`() {
        val records = listOf(
            aSubmissionRecord(action = GradingAction.SUBMIT, verdict = Verdict.PASS),
            aSubmissionRecord(action = GradingAction.RUN, verdict = Verdict.COMPILE_ERROR),
            aSubmissionRecord(action = GradingAction.RUN, verdict = Verdict.RUNTIME_ERROR),
        )

        SubmissionTally.of(records, TallyGroup.VERDICT).map { it.key }.shouldContainExactly("PASS")
    }

    /** A history of runs alone is not an empty history wrongly reported — it has no submissions. */
    @Test
    fun `runs alone tally to nothing`() {
        val records = listOf(aSubmissionRecord(action = GradingAction.RUN))

        TallyGroup.entries.forEach { group -> SubmissionTally.of(records, group).shouldBeEmpty() }
    }

    @Test
    fun `counts by language`() {
        val records = listOf(aSubmissionRecord(language = "java"), aSubmissionRecord(language = "mysql"))

        SubmissionTally.of(records, TallyGroup.LANGUAGE)
            .shouldContainExactly(TallyBucket("java", null, 1, null), TallyBucket("mysql", null, 1, null))
    }

    @Test
    fun `a blank language is a missing key, not an empty-string bucket`() {
        SubmissionTally.of(listOf(aSubmissionRecord(language = "")), TallyGroup.LANGUAGE)
            .shouldContainExactly(TallyBucket(null, null, 1, null))
    }

    @Test
    fun `counts by problem and labels the id with the recorded title`() {
        val records = listOf(
            aSubmissionRecord(lessonId = 120804, title = "two numbers"),
            aSubmissionRecord(lessonId = 120804, title = "two numbers"),
            aSubmissionRecord(lessonId = 131528, title = "ice cream"),
        )

        SubmissionTally.of(records, TallyGroup.PROBLEM).shouldContainExactly(
            TallyBucket("120804", "two numbers", 2, null),
            TallyBucket("131528", "ice cream", 1, null),
        )
    }

    @Test
    fun `a problem with no recorded title gets no label`() {
        val bucket = SubmissionTally.of(listOf(aSubmissionRecord(title = "")), TallyGroup.PROBLEM).single()

        bucket.key shouldBe "120804"
        bucket.label.shouldBeNull()
    }

    @Test
    fun `orders by count, then by key, so a client can cache and diff the answer`() {
        val records = listOf(
            aSubmissionRecord(language = "python3"),
            aSubmissionRecord(language = "java"),
            aSubmissionRecord(language = "java"),
            aSubmissionRecord(language = "cpp"),
        )

        SubmissionTally.of(records, TallyGroup.LANGUAGE).map { it.key }
            .shouldContainExactly("java", "cpp", "python3")
    }

    @Test
    fun `sorts the keyless bucket last whatever its size`() {
        val records = listOf(
            aSubmissionRecord(verdict = null, outcome = Outcome.INCOMPLETE),
            aSubmissionRecord(verdict = null, outcome = Outcome.INCOMPLETE),
            aSubmissionRecord(verdict = null, outcome = Outcome.INCOMPLETE),
            aSubmissionRecord(verdict = Verdict.PASS),
        )

        SubmissionTally.of(records, TallyGroup.VERDICT).map { it.key }
            .shouldContainExactly("PASS", null)
    }

    @Test
    fun `an empty history tallies to nothing rather than failing`() {
        TallyGroup.entries.forEach { group -> SubmissionTally.of(emptyList(), group).shouldBeEmpty() }
    }

    @Test
    fun `reads a group name in any case, with whitespace`() {
        TallyGroup.from("verdict") shouldBe TallyGroup.VERDICT
        TallyGroup.from("  LANGUAGE ") shouldBe TallyGroup.LANGUAGE
        TallyGroup.from("Problem") shouldBe TallyGroup.PROBLEM
    }

    @Test
    fun `refuses a group it cannot honour, and says what it accepts`() {
        val thrown = shouldThrow<IllegalArgumentException> { TallyGroup.from("tag") }

        thrown.message.shouldContain("verdict")
        thrown.message.shouldContain("language")
        thrown.message.shouldContain("problem")
    }

    @Test
    fun `refuses an empty group name`() {
        shouldThrow<IllegalArgumentException> { TallyGroup.from("") }
    }

    @Test
    fun `counts by part, and a part bucket also counts the problems in it`() {
        val records = listOf(
            aSubmissionRecord(lessonId = 1, part = "SELECT", verdict = Verdict.PASS),
            aSubmissionRecord(lessonId = 2, part = "SELECT", verdict = Verdict.WRONG),
        )

        val bucket = SubmissionTally.of(records, TallyGroup.PART).single()

        bucket.key shouldBe "SELECT"
        bucket.count shouldBe 2
        bucket.progress.shouldNotBeNull().attempted shouldBe 2
        bucket.progress.shouldNotBeNull().passed shouldBe 1
    }

    @Test
    fun `counts by level, keyed by the level as text`() {
        val bucket = SubmissionTally.of(listOf(aSubmissionRecord(level = 2)), TallyGroup.LEVEL).single()

        bucket.key shouldBe "2"
        bucket.progress.shouldNotBeNull().attempted shouldBe 1
    }

    /** Absent is not level 0 ([[concepts/assumption-vs-measurement]]). */
    @Test
    fun `a problem with no recorded level or part is a missing key`() {
        SubmissionTally.of(listOf(aSubmissionRecord(level = null)), TallyGroup.LEVEL).single().key.shouldBeNull()
        SubmissionTally.of(listOf(aSubmissionRecord(part = " ")), TallyGroup.PART).single().key.shouldBeNull()
    }

    /** Records captured before the catalog knew a problem carry no level; the problem still has one. */
    @Test
    fun `one problem is one bucket even when its early records carry no level`() {
        val records = listOf(
            aSubmissionRecord(lessonId = 1, level = null, ts = at("10:00"), verdict = Verdict.WRONG),
            aSubmissionRecord(lessonId = 1, level = 2, ts = at("10:05"), verdict = Verdict.PASS),
        )

        val bucket = SubmissionTally.of(records, TallyGroup.LEVEL).single()

        bucket.key shouldBe "2"
        bucket.count shouldBe 2
        bucket.progress.shouldNotBeNull().attempted shouldBe 1
        bucket.progress.shouldNotBeNull().passedFirstSubmit shouldBe 0
    }

    @Test
    fun `a problem with no level on any record goes to the missing-key bucket`() {
        val unleveled = aSubmissionRecord(lessonId = 1, level = null)
        val records = listOf(unleveled, unleveled)

        val bucket = SubmissionTally.of(records, TallyGroup.LEVEL).single()

        bucket.key.shouldBeNull()
        bucket.progress.shouldNotBeNull().attempted shouldBe 1
    }

    @Test
    fun `a part with only runs makes no bucket`() {
        val run = aSubmissionRecord(part = "SELECT", action = GradingAction.RUN, attempt = 0)

        SubmissionTally.of(listOf(run), TallyGroup.PART).shouldBeEmpty()
    }

    @Test
    fun `runs mixed in change neither the counts nor the progress of other groupings`() {
        val run = aSubmissionRecord(action = GradingAction.RUN, attempt = 0, verdict = Verdict.WRONG)

        TallyGroup.entries.filterNot { it.countsProblems() }.forEach {
            val buckets = SubmissionTally.of(listOf(aSubmissionRecord(), run), it)

            buckets.sumOf { bucket -> bucket.count } shouldBe 1
            buckets.forEach { bucket -> bucket.progress.shouldBeNull() }
        }
    }

    private fun at(time: String) = OffsetDateTime.parse("2026-10-01T$time:00+09:00")

    /** A problem "passed" inside the WRONG bucket would be noise, so verdict buckets carry counts only. */
    @Test
    fun `groupings that are not about problems carry no problem counts`() {
        SubmissionTally.of(listOf(aSubmissionRecord()), TallyGroup.VERDICT).single().progress.shouldBeNull()
    }

    @Test
    fun `names every group on the wire in lower case`() {
        TallyGroup.wireNames().shouldContainExactly("verdict", "language", "problem", "part", "level")
    }
}
