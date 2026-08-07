package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Text assertions for the JavaScript generator; [JavascriptRunnerExecutionTest] running the
 * output under a real `node` is what earns the language its status (#82). The JS-specific
 * luxury: §7.1 example values are JSON, and JSON is valid JavaScript — literals embed
 * verbatim, the one runner with no type mapping to get wrong.
 */
class JavascriptRunnerTest {
    @Test
    fun `a solution-style problem gets a runner that loads the script and calls solution`() {
        val code = "function solution(num1, num2) {\n    return num1 * num2;\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val runner = JavascriptRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.fileName shouldBe "runner_test.js"
        runner.source shouldContain "vm.runInThisContext"
        runner.source shouldContain "check(1, solution(6, 7), 42);"
    }

    /** JSON is JavaScript: arrays, booleans and null embed verbatim. */
    @Test
    fun `json values embed verbatim`() {
        val code = "function solution(flag, xs) { return null; }"
        val examples = listOf(ProblemExample("true, [[1, 2], [3, 4]]", "null"))

        val runner = JavascriptRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "check(1, solution(true, [[1,2],[3,4]]), null);"
    }

    @Test
    fun `strings are quoted and escaped`() {
        val code = "function solution(s) { return s; }"
        val examples = listOf(ProblemExample("\"a \\\"quoted\\\" one\"", "\"a \\\"quoted\\\" one\""))

        val runner = JavascriptRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "solution(\"a \\\"quoted\\\" one\")"
    }

    @Test
    fun `a main-style problem gets a subprocess-per-example runner`() {
        val code = "const readline = require('readline');\n" +
            "const rl = readline.createInterface({ input: process.stdin });\n" +
            "let input = [];\n" +
            "rl.on('line', function (line) { input = line.split(' '); });"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val runner = JavascriptRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "spawnSync"
        runner.source shouldContain "check(1, \"4 5\", \"a = 4\\nb = 5\");"
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `no examples refuses with the reason`() {
        JavascriptRunner.generate("function solution(a) { return a; }", emptyList())
            .shouldBeInstanceOf<Runner.Refused>().reason shouldContain "no examples"
    }

    @Test
    fun `an arity mismatch refuses rather than truncating`() {
        val refused = JavascriptRunner.generate(
            "function solution(a) { return a; }",
            listOf(ProblemExample("1, 2, 3", "6")),
        ).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "3 argument"
    }

    /** An arrow stays script-scoped when loaded — a runner calling it would throw, so refuse. */
    @Test
    fun `an arrow-assigned solution refuses with the declaration-form reason`() {
        JavascriptRunner.generate(
            "const solution = (a, b) => a + b;",
            listOf(ProblemExample("1, 2", "3")),
        ).shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `an unrecognised shape refuses`() {
        JavascriptRunner.generate("console.log(1);", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }
}
