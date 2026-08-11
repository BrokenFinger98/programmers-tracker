package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.UnknownReason
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aRunErrorText
import com.brokenfinger.tracker.support.fixtures.aSqlChannel
import com.brokenfinger.tracker.support.fixtures.anAssembledSession
import com.brokenfinger.tracker.support.fixtures.anAssembler
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The five measured verdicts, each driven end to end from its own capture (dev rules §6.2,
 * §6.3). A verdict is only reported once a terminal frame for that (action × kind) cell has
 * arrived — see [com.brokenfinger.tracker.domain.calc.TerminationRule].
 */
class GradingSessionAssemblerTest {
    @Test
    fun `an algorithm submit that passed is judged from its finish frame`() {
        val session = anAssembledSession("algorithm-pass.jsonl")

        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.PASS
        session.action shouldBe GradingAction.SUBMIT
        session.kind shouldBe ProblemKind.ALGORITHM
        session.testcasesComplete shouldBe true
        session.testcases.map { it.id } shouldContainExactly listOf(154893L, 154894L)
    }

    /**
     * The run success path, end to end. This cell terminates at `result` rather than
     * `finish`, its cases identify themselves by 0-based `index`, and in this capture
     * index 1 genuinely arrived before index 0 — measured in our own live verification
     * (fixtures/algorithm-run-pass.jsonl, issues #6 and #10).
     */
    /**
     * The cached-result path, from our own live capture (#74, protocol §13.2, §15 #16): a
     * submit answered from cache carries `start` then a terminal `error` and **no verdict
     * frames at all** — while the browser renders the cached scoreboard. UNKNOWN is correct;
     * the error text must survive onto the session so the classifier can name the reason.
     */
    @Test
    fun `a cached-result submit settles UNKNOWN with the measured error text preserved`() {
        val session = anAssembledSession("algorithm-cached-result.jsonl")

        session.outcome shouldBe Outcome.UNKNOWN
        session.verdict shouldBe null
        session.errorText shouldBe "같은 코드로 채점한 결과가 있습니다."
        UnknownReason.of(session.outcome, session.errorText) shouldBe UnknownReason.CACHED_RESULT
    }

    @Test
    fun `an algorithm run that passed is judged from its result frame`() {
        val session = anAssembledSession("algorithm-run-pass.jsonl")

        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.PASS
        session.action shouldBe GradingAction.RUN
        session.kind shouldBe ProblemKind.ALGORITHM
        session.testcases.map { it.id } shouldContainExactly listOf(0L, 1L)
    }

    /**
     * A run promises its work as the example testcases on `start`, never as `testcase_ids`.
     * Checking only for ids filed every run as unverified — a systematically misleading flag
     * on the most common action (measured end to end 2026-08-05, issue #23).
     */
    @Test
    fun `a run is complete when every announced example reported back`() {
        anAssembledSession("algorithm-run-pass.jsonl").testcasesComplete shouldBe true
    }

    @Test
    fun `a partially scored submit is judged wrong from the failing testcase`() {
        val session = anAssembledSession("algorithm-wrong.jsonl")

        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.WRONG
        session.testcasesComplete shouldBe true
    }

    @Test
    fun `a timed out submit is judged timeout even though it reports no run time`() {
        val session = anAssembledSession("algorithm-timeout.jsonl")

        session.verdict shouldBe Verdict.TIMEOUT
        session.testcases.first().runTime.shouldBeNull()
    }

    // The submit path reports compile and runtime failures with the same string
    // (protocol doc §7), so without a bound run the honest answer is a runtime error.
    @Test
    fun `an unbound runtime failure stays a runtime error`() {
        anAssembledSession("algorithm-runtime.jsonl").verdict shouldBe Verdict.RUNTIME_ERROR
    }

    @Test
    fun `a bound stack trace confirms the runtime error`() {
        val session = anAssembledSession("algorithm-runtime.jsonl", boundErrorText = aRunErrorText(1))

        session.verdict shouldBe Verdict.RUNTIME_ERROR
    }

    @Test
    fun `a bound compiler diagnostic promotes the same submit response to a compile error`() {
        val session = anAssembledSession("algorithm-compile.jsonl", boundErrorText = aRunErrorText(0))

        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.COMPILE_ERROR
    }

    // SQL submits never send finish; waiting for one hangs forever (protocol doc §6).
    @Test
    fun `a database submit is judged at result_lesson_challenge`() {
        val session = anAssembledSession("sql-pass.jsonl", channel = aSqlChannel())

        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.PASS
        session.kind shouldBe ProblemKind.DATABASE
        session.testcasesComplete shouldBe true
    }

    // A database run does send finish, and reports its only result on that same frame.
    @Test
    fun `a database run is judged at finish from the result it carries`() {
        val session = anAssembledSession("sql-run.jsonl", channel = aSqlChannel())

        session.action shouldBe GradingAction.RUN
        session.verdict shouldBe Verdict.PASS
        session.testcases.map { it.id } shouldContainExactly listOf(5437L)
        session.testcasesComplete shouldBe true
    }

    // For algorithm submits result_lesson_challenge arrives before finish; the late finish
    // belongs to the same grading, not to a new one (design §4.2).
    @Test
    fun `a late finish after result_lesson_challenge is absorbed into the same session`() {
        val frames = FixtureLoader.facts("algorithm-pass.jsonl")
        val assembler = anAssembler()

        frames.take(5).forEach(assembler::accept)
        val beforeFinish = assembler.hasTerminated()
        assembler.accept(frames[5])

        beforeFinish shouldBe false
        assembler.hasTerminated() shouldBe true
        assembler.settle().frames shouldContainExactly frames
    }

    /**
     * The run path reports one error frame per diagnostic and ends on `result` (protocol doc
     * §7), so this capture — `start`, `error`, `error` — never terminated: the `result` was
     * not captured with it. What matters is that the text is exposed either way, because
     * that text is what later promotes an indistinguishable submit response (#152).
     */
    @Test
    fun `a run error does not end the stream, and its text is exposed for promotion anyway`() {
        val session = anAssembledSession("algorithm-run-error.jsonl")

        session.action shouldBe GradingAction.RUN
        session.outcome shouldBe Outcome.INCOMPLETE
        session.verdict.shouldBeNull()
        checkNotNull(session.errorText) shouldContain "/Solution.java:3: error:"
    }
}
