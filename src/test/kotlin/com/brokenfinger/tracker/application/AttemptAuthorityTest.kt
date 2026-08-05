package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.support.fixtures.aRecord
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AttemptAuthorityTest {
    @Test
    fun `the first submit of an unseen problem is attempt one`() {
        val authority = AttemptAuthority.from(emptyList())

        authority.allocate(120804, GradingAction.SUBMIT) shouldBe 1
    }

    @Test
    fun `each submit takes the next number`() {
        val authority = AttemptAuthority.from(emptyList())

        val allocated = (1..3).map { authority.allocate(120804, GradingAction.SUBMIT) }

        allocated shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `a run keeps the previous submit's number and never advances it`() {
        val authority = AttemptAuthority.from(emptyList())
        authority.allocate(120804, GradingAction.SUBMIT)

        authority.allocate(120804, GradingAction.RUN) shouldBe 1
        authority.allocate(120804, GradingAction.RUN) shouldBe 1
        authority.allocate(120804, GradingAction.SUBMIT) shouldBe 2
    }

    @Test
    fun `a run before the first submit belongs to no attempt`() {
        val authority = AttemptAuthority.from(emptyList())

        authority.allocate(120804, GradingAction.RUN) shouldBe AttemptAuthority.NONE
    }

    @Test
    fun `problems are counted independently`() {
        val authority = AttemptAuthority.from(emptyList())
        authority.allocate(120804, GradingAction.SUBMIT)
        authority.allocate(120804, GradingAction.SUBMIT)

        authority.allocate(131528, GradingAction.SUBMIT) shouldBe 1
    }

    @Test
    fun `restores the next number from the log rather than from a directory scan`() {
        val authority = AttemptAuthority.from(listOf(aRecord(attempt = 1), aRecord(attempt = 2)))

        authority.allocate(120804, GradingAction.SUBMIT) shouldBe 3
    }

    @Test
    fun `restore takes the highest number, not the line count — runs repeat a number`() {
        val history =
            listOf(
                aRecord(action = GradingAction.SUBMIT, attempt = 1),
                aRecord(action = GradingAction.RUN, attempt = 1),
                aRecord(action = GradingAction.RUN, attempt = 1),
            )

        AttemptAuthority.from(history).allocate(120804, GradingAction.SUBMIT) shouldBe 2
    }

    @Test
    fun `restore survives a log whose highest number is not on the last line`() {
        val history = listOf(aRecord(attempt = 7), aRecord(attempt = 5))

        AttemptAuthority.from(history).allocate(120804, GradingAction.SUBMIT) shouldBe 8
    }

    @Test
    fun `numbering is monotonic across languages, so the sequence reads as one history`() {
        val history = listOf(aRecord(attempt = 1, language = "java"), aRecord(attempt = 2, language = "mysql"))

        AttemptAuthority.from(history).allocate(120804, GradingAction.SUBMIT) shouldBe 3
    }

    @Test
    fun `restore keeps problems apart`() {
        val history = listOf(aRecord(lessonId = 120804, attempt = 4), aRecord(lessonId = 131528, attempt = 1))

        AttemptAuthority.from(history).allocate(131528, GradingAction.SUBMIT) shouldBe 2
    }

    @Test
    fun `a record whose action we no longer recognise still holds its number`() {
        val history = listOf(aRecord(action = null, attempt = 9))

        AttemptAuthority.from(history).latestOf(120804) shouldBe 9
    }

    @Test
    fun `the latest number of an unseen problem is none`() {
        AttemptAuthority.from(emptyList()).latestOf(999999) shouldBe AttemptAuthority.NONE
    }
}
