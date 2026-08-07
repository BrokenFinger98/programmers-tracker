package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Text assertions for the Python generator; `PythonRunnerExecutionTest` running real
 * `python3` children is what earns the language its status (#78). Same line as Java: every
 * mismatch refuses with a reason, never a best-effort file — and Python's untyped parameters
 * make the refusals *more* load-bearing, not less, because type errors cannot be caught here
 * at all.
 */
class PythonRunnerTest {
    @Test
    fun `a solution-style problem gets a runner that calls solution with literals`() {
        val code = "def solution(num1, num2):\n    return num1 * num2"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val runner = PythonRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.fileName shouldBe "runner_test.py"
        runner.source shouldContain "Solution.solution(6, 7)"
    }

    /** JSON keywords differ from Python's in exactly three spellings. */
    @Test
    fun `json booleans nulls and arrays become Python literals`() {
        val code = "def solution(flag, xs):\n    return None"
        val examples = listOf(ProblemExample("true, [[1, 2], [3, 4]]", "null"))

        val runner = PythonRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "Solution.solution(True, [[1, 2], [3, 4]])"
        runner.source shouldContain "None"
    }

    @Test
    fun `strings are quoted and escaped`() {
        val code = "def solution(s):\n    return s"
        val examples = listOf(ProblemExample("\"a \\\"quoted\\\" one\"", "\"a \\\"quoted\\\" one\""))

        val runner = PythonRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "Solution.solution(\"a \\\"quoted\\\" one\")"
    }

    @Test
    fun `a main-style problem gets a subprocess-per-example runner`() {
        val code = "a, b = map(int, input().split())\nprint(f\"a = {a}\")\nprint(f\"b = {b}\")"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val runner = PythonRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "subprocess.run"
        runner.source shouldContain "a = 4\\nb = 5"
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `no examples refuses with the reason`() {
        PythonRunner.generate("def solution(a):\n    return a", emptyList())
            .shouldBeInstanceOf<Runner.Refused>().reason shouldContain "no examples"
    }

    @Test
    fun `an arity mismatch refuses rather than truncating`() {
        val refused = PythonRunner.generate(
            "def solution(a):\n    return a",
            listOf(ProblemExample("1, 2, 3", "6")),
        ).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "3 argument"
    }

    @Test
    fun `varargs refuse because the arity check would be meaningless`() {
        PythonRunner.generate("def solution(*args):\n    return 0", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `an unrecognised shape refuses`() {
        PythonRunner.generate("print(1 + 1)", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }
}
