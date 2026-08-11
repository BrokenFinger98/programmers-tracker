package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure calculator — no mocks, no I/O (dev rules §3). Failure paths are mandatory:
 * all five verdicts plus the unrecognized case (§6.3).
 */
class VerdictResolverTest {
    @Test
    fun `all testcases passing is PASS`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = true), aTestcaseResult(id = 2, passed = true)),
            boundErrorText = null,
        )

        verdict shouldBe Verdict.PASS
    }

    @Test
    fun `a failure carrying a runtime is WRONG`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패 (0.01ms, 75.3MB)", runTime = "0.01")),
            boundErrorText = null,
        )

        verdict shouldBe Verdict.WRONG
    }

    @Test
    fun `a timeout message is TIMEOUT even though runTime is null`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패 (시간 초과)", runTime = null)),
            boundErrorText = null,
        )

        verdict shouldBe Verdict.TIMEOUT
    }

    @Test
    fun `a runtime-error message without a bound run record stays RUNTIME_ERROR`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패 (런타임 에러)", runTime = null)),
            boundErrorText = null,
        )

        verdict shouldBe Verdict.RUNTIME_ERROR
    }

    @Test
    fun `a compiler diagnostic in the bound run record promotes to COMPILE_ERROR`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패 (런타임 에러)")),
            boundErrorText = "/Solution.java:5: error: ';' expected",
        )

        verdict shouldBe Verdict.COMPILE_ERROR
    }

    @Test
    fun `a stack trace in the bound run record stays RUNTIME_ERROR`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패 (런타임 에러)")),
            boundErrorText = "Exception in thread \"main\" java.lang.ArithmeticException: / by zero",
        )

        verdict shouldBe Verdict.RUNTIME_ERROR
    }

    /**
     * The memory-limit message has never been triggered, so its string is unknown
     * (protocol doc §14). Coercing it into a neighbouring verdict is the silent-wrong-data
     * failure the constitution ranks worst.
     */
    @Test
    fun `an unrecognized failure message resolves to no verdict`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패 (메모리 초과)")),
            boundErrorText = null,
        )

        verdict.shouldBeNull()
    }

    @Test
    fun `a failure with no message at all resolves to no verdict`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = null)),
            boundErrorText = null,
        )

        verdict.shouldBeNull()
    }

    @Test
    fun `no testcases at all resolves to no verdict rather than a vacuous pass`() {
        VerdictResolver.resolve(testcases = emptyList(), boundErrorText = null).shouldBeNull()
    }

    @Test
    fun `the first failing testcase decides, regardless of arrival order`() {
        val shuffled = listOf(
            aTestcaseResult(id = 3, passed = true),
            aTestcaseResult(id = 1, passed = false, msg = "실패 (시간 초과)"),
            aTestcaseResult(id = 2, passed = true),
        )

        VerdictResolver.resolve(shuffled, boundErrorText = null) shouldBe Verdict.TIMEOUT
    }

    // Nothing ran ------------------------------------------------------------------------------

    /**
     * A compile failure reports no testcase at all — the run path emits `start` and then one
     * error frame per diagnostic (protocol doc §7). Until #151 that returned null and the
     * verdict the README advertises was filed as UNKNOWN. Measured 2026-08-11 on 181946.
     */
    @Test
    fun `a compiler diagnostic with nothing run is a compile error`() {
        val text = "/Solution.java:7: error: unreported exception IOException\n2 errors"

        VerdictResolver.resolve(emptyList(), text) shouldBe Verdict.COMPILE_ERROR
    }

    @Test
    fun `an error text that is not a compiler diagnostic with nothing run is a runtime error`() {
        VerdictResolver.resolve(emptyList(), "Exception in thread \"main\"") shouldBe Verdict.RUNTIME_ERROR
    }

    /**
     * The trap in the obvious fix. A cached result is also a terminal error frame with no
     * testcases, and reading its text as a failure would invent one the learner never had.
     */
    @Test
    fun `every recognised unknown stays unknown rather than becoming a failure`() {
        UnknownReason.entries.forEach { reason ->
            withClue(reason.label) {
                VerdictResolver.resolve(emptyList(), reason.measuredText).shouldBeNull()
            }
        }
    }

    @Test
    fun `nothing run and nothing said stays unknown`() {
        VerdictResolver.resolve(emptyList(), null).shouldBeNull()
    }

    /**
     * Python, measured 2026-08-11 on lesson 120805 — a `def` missing its colon. The whole
     * point of this case is that `:\d+: error:` is **javac's** shape and matches nothing here,
     * so a compile failure was filed as RUNTIME_ERROR (#162).
     */
    @Test
    fun `a python syntax error is a compile error`() {
        val measured = """
            Traceback (most recent call last):
              File "/solution_test.py", line 6, in <module>
                from solution import *
              File "/solution.py", line 3
                def solution(num1, num2) return num1 // num2
                                         ^^^^^^
            SyntaxError: expected ':'
        """.trimIndent()

        VerdictResolver.resolve(emptyList(), measured) shouldBe Verdict.COMPILE_ERROR
    }

    /**
     * A traceback with no compile diagnostic in it is a runtime failure, which is the case the
     * Python match must not swallow.
     */
    @Test
    fun `a python runtime traceback stays a runtime error`() {
        val traceback = """
            Traceback (most recent call last):
              File "/solution.py", line 2, in solution
                return num1 // 0
            ZeroDivisionError: integer division or modulo by zero
        """.trimIndent()

        VerdictResolver.resolve(emptyList(), traceback) shouldBe Verdict.RUNTIME_ERROR
    }
}
