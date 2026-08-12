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
import io.kotest.assertions.withClue
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
     * The cached-result path, measured whole (#154). A submit answered from cache carries
     * `start`, then an `error` saying so — **and then grades anyway**: `test_group`, eighteen
     * testcases, `result_lesson_challenge`, `finish`. Measured on lesson 120802, 2026-08-11.
     *
     * The earlier fixture stopped at the error and was read as "no verdict frames at all".
     * It was not the protocol; it was this code closing the stream and filing the remaining
     * twenty-one frames as orphans. The verdict below is the one the learner actually got.
     */
    @Test
    fun `a cached-result submit is graded anyway, and the grading is what counts`() {
        val session = anAssembledSession("algorithm-cached-then-graded.jsonl")

        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.PASS
        session.testcases.size shouldBe 18
        // The cache notice survives onto the session even though it no longer decides anything.
        session.errorText shouldBe "같은 코드로 채점한 결과가 있습니다."
    }

    /**
     * The truncated half, kept as the capture it is. It never terminates now, which is the
     * honest answer to a stream whose end was never recorded — and it is exactly what a
     * grading interrupted mid-flight looks like.
     */
    @Test
    fun `the half of a cached-result submit that was captured before 154 never terminates`() {
        val session = anAssembledSession("algorithm-cached-result.jsonl")

        session.outcome shouldBe Outcome.INCOMPLETE
        session.verdict shouldBe null
        UnknownReason.of(session.outcome, session.errorText) shouldBe null
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

    /**
     * **Every supported language's compile failure, each from its own capture** — the whole
     * `GENERATORS` list of `FileDerivedArtifacts`, broken on purpose on lesson 181952 and
     * measured on 2026-08-12 (#212).
     *
     * The reason this is a table and not four more one-off tests is that the interesting
     * property is *coverage*: the previous two entries in `compilerDiagnostics` classified
     * six of the seven, and only two of those six on purpose. A language added to the
     * generator list without a row here is a language whose compile failures are recorded as
     * something the learner never did, and this table is where that becomes visible.
     *
     * Each capture is whole — `start · error · result` — so it also pins that the run path
     * ends at `result` for a failure that never reached a testcase.
     */
    @Test
    fun `a compile failure is a compile error in every language the server supports`() {
        val measured = mapOf(
            "java-compile-error.jsonl" to "/Solution.java:7: error:",
            "cpp-compile-error.jsonl" to "/solution0.cpp:8:15: error:",
            "c-compile-error.jsonl" to "/solution0.c:6:21: error:",
            "kotlin-compile-error.jsonl" to "/Solution0.kt:3:15: error:",
            "csharp-compile-error.jsonl" to "error CS1002:",
            "javascript-compile-error.jsonl" to "SyntaxError: missing )",
            "python-indentation-error.jsonl" to "IndentationError: unexpected indent",
            "python-tab-error.jsonl" to "TabError: inconsistent use of tabs and spaces",
        )

        measured.forEach { (fixture, diagnostic) ->
            withClue(fixture) {
                val session = anAssembledSession(fixture)

                session.action shouldBe GradingAction.RUN
                session.outcome shouldBe Outcome.JUDGED
                session.verdict shouldBe Verdict.COMPILE_ERROR
                checkNotNull(session.errorText) shouldContain diagnostic
            }
        }
    }

    /**
     * Kotlin's other way to fail, and the reason it is *not* in the table above. Programmers
     * invokes `main(String[])`; a top-level `fun main()` with no parameters compiles fine and
     * is then never found. What comes back is a Programmers message saying no main method was
     * defined — not a compiler one — so RUNTIME_ERROR is the right answer: nothing failed to
     * compile.
     *
     * It cost two confounded readings before the editor template was checked, because the
     * broken body and the correct body returned the identical message (protocol doc §7.2).
     */
    @Test
    fun `a kotlin main with the wrong signature is a runtime error, not a compile error`() {
        val session = anAssembledSession("kotlin-missing-main.jsonl")

        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.RUNTIME_ERROR
        checkNotNull(session.errorText) shouldContain "main 메소드가 정의되지 않았습니다"
    }
}
