package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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
        buckets.shouldContainExactly(TallyBucket("WRONG", null, 2), TallyBucket("PASS", null, 1))
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

        buckets.shouldContainExactly(TallyBucket("PASS", null, 1), TallyBucket(null, null, 2))
        buckets.last().key.shouldBeNull()
    }

    @Test
    fun `counts by language`() {
        val records = listOf(aSubmissionRecord(language = "java"), aSubmissionRecord(language = "mysql"))

        SubmissionTally.of(records, TallyGroup.LANGUAGE)
            .shouldContainExactly(TallyBucket("java", null, 1), TallyBucket("mysql", null, 1))
    }

    @Test
    fun `a blank language is a missing key, not an empty-string bucket`() {
        SubmissionTally.of(listOf(aSubmissionRecord(language = "")), TallyGroup.LANGUAGE)
            .shouldContainExactly(TallyBucket(null, null, 1))
    }

    @Test
    fun `counts by problem and labels the id with the recorded title`() {
        val records = listOf(
            aSubmissionRecord(lessonId = 120804, title = "two numbers"),
            aSubmissionRecord(lessonId = 120804, title = "two numbers"),
            aSubmissionRecord(lessonId = 131528, title = "ice cream"),
        )

        SubmissionTally.of(records, TallyGroup.PROBLEM).shouldContainExactly(
            TallyBucket("120804", "two numbers", 2),
            TallyBucket("131528", "ice cream", 1),
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
    fun `names every group on the wire in lower case`() {
        TallyGroup.wireNames().shouldContainExactly("verdict", "language", "problem")
    }
}
