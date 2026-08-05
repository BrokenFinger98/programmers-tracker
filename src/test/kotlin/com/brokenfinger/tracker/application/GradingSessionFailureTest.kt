package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aSessionOf
import com.brokenfinger.tracker.support.fixtures.anAssembler
import com.brokenfinger.tracker.support.fixtures.anUnmeasuredFrame
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The paths that are not a clean verdict (dev rules §6.3). Every one of them exists because
 * recording nothing, or recording a guess, is worse than recording a partial truth
 * ([[decisions/2026-08-05-failure-taxonomy]]).
 */
class GradingSessionFailureTest {
    private val passStream = FixtureLoader.facts("algorithm-pass.jsonl")
    private val wrongStream = FixtureLoader.facts("algorithm-wrong.jsonl")

    // A memory-limit verdict has never been triggered, so its msg string is unknown
    // (protocol doc §14). This is the one case that cannot have a capture, so the measured
    // timeout stream is amended in the single field under test (dev rules §6.2).
    @Test
    fun `a terminal frame with an uncaptured failure message is unknown, never coerced`() {
        val session = aSessionOf(FixtureLoader.facts("algorithm-timeout.jsonl").map(::asMemoryLimit))

        session.outcome shouldBe Outcome.UNKNOWN
        session.verdict.shouldBeNull()
        session.testcases.map { it.id } shouldContainExactly listOf(154901L, 154902L)
    }

    // Settling is driven by the caller — a fired timeout or a dropped socket — so the
    // assembler needs no clock of its own and the test needs no sleep.
    @Test
    fun `settling before a terminal frame is incomplete and keeps every frame`() {
        val truncated = passStream.dropLast(1)

        val session = aSessionOf(truncated)

        session.outcome shouldBe Outcome.INCOMPLETE
        session.verdict.shouldBeNull()
        session.frames shouldContainExactly truncated
        session.testcases.map { it.id } shouldContainExactly listOf(154893L, 154894L)
    }

    @Test
    fun `a session that received nothing at all is incomplete rather than empty-passing`() {
        val session = anAssembler().settle()

        session.outcome shouldBe Outcome.INCOMPLETE
        session.action.shouldBeNull()
        session.frames.shouldContainExactly(emptyList())
    }

    // Grading is parallel, so testcases arrive out of order — index 1 before index 0 was
    // measured twice (protocol doc §5, [[decisions/2026-08-05-failure-taxonomy]]).
    @Test
    fun `testcases that arrive out of order are ordered by id before judging`() {
        val reordered = listOf(wrongStream[0], wrongStream[1], wrongStream[3], wrongStream[2]) + wrongStream.drop(4)

        val session = aSessionOf(reordered)

        session.testcases.map { it.id } shouldContainExactly listOf(154801L, 154802L)
        session.verdict shouldBe Verdict.WRONG
        session.testcasesComplete shouldBe true
    }

    // Sorting alone assumes every testcase arrived. Here the only failing one never did:
    // without the completeness flag the session would summarize as a clean pass.
    @Test
    fun `a testcase that never arrived is marked incomplete instead of being summarized away`() {
        val session = aSessionOf(wrongStream.filterIndexed { index, _ -> index != 3 })

        session.testcasesComplete shouldBe false
        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.PASS
        session.testcases.map { it.id } shouldContainExactly listOf(154801L)
    }

    @Test
    fun `a stream that announced no expected ids is never claimed complete`() {
        val session = aSessionOf(FixtureLoader.facts("algorithm-run-error.jsonl"))

        session.testcasesComplete shouldBe false
    }

    // Dropping what we do not recognise is the only way to miss a protocol change
    // (dev rules §2.3), so an unknown frame is kept and left inert.
    @Test
    fun `an unknown message type is preserved without terminating or judging the session`() {
        val withNotice = passStream.take(5) + anUnmeasuredFrame() + passStream[5]

        val session = aSessionOf(withNotice)

        session.frames shouldContainExactly withNotice
        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.PASS
    }

    private fun asMemoryLimit(frame: GradingFrameFacts): GradingFrameFacts {
        val graded = frame.testcase ?: return frame
        return frame.copy(testcase = graded.copy(msg = "실패 (메모리 초과)", runTime = null, memorySize = null))
    }
}
