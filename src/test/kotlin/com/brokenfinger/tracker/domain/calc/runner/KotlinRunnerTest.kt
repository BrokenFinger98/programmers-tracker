package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Text assertions for the Kotlin generator; [KotlinRunnerExecutionTest] compiling and
 * running the output for real is what earns the language its status (#84). Kotlin's two
 * traps are both about `main`: the harness must be an `object RunnerTest` with a
 * `@JvmStatic` entry (a top-level `fun main` would clash with the user's), and the user's
 * `main` must be reached through a **top-level bridge** — inside the object, an unqualified
 * `main(...)` call would resolve to the harness's own entry, not the user's.
 */
class KotlinRunnerTest {
    @Test
    fun `a solution-style problem gets an object harness calling Solution`() {
        val code = "class Solution {\n    fun solution(num1: Int, num2: Int): Int {\n" +
            "        return num1 * num2\n    }\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val runner = KotlinRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.fileName shouldBe "runner_test.kt"
        runner.source shouldContain "object RunnerTest"
        runner.source shouldContain "@JvmStatic"
        runner.source shouldContain "check(1, Solution().solution(6, 7), 42)"
    }

    /** Measured 120817/12950 spellings: primitive arrays, explicit arrayOf type argument. */
    @Test
    fun `arrays become typed constructions`() {
        val code = "class Solution {\n" +
            "    fun solution(numbers: IntArray, words: Array<String>): Array<IntArray> {\n" +
            "        return arrayOf()\n    }\n}"
        val examples = listOf(ProblemExample("[1, 2, 3], [\"a\"]", "[[1], [2]]"))

        val runner = KotlinRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "intArrayOf(1, 2, 3)"
        runner.source shouldContain "arrayOf<String>(\"a\")"
        runner.source shouldContain "arrayOf<IntArray>(intArrayOf(1), intArrayOf(2))"
    }

    /** Kotlin arrays compare by reference with ==; the harness must compare by content. */
    @Test
    fun `the harness compares arrays by content`() {
        val code = "class Solution {\n    fun solution(numbers: IntArray): IntArray {\n" +
            "        return numbers\n    }\n}"
        val examples = listOf(ProblemExample("[1, 2]", "[2, 1]"))

        val runner = KotlinRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "contentEquals"
        runner.source shouldContain "contentDeepEquals"
    }

    /** A dollar sign in a string literal would be a template in the generated Kotlin. */
    @Test
    fun `dollar signs in strings are escaped`() {
        val code = "class Solution {\n    fun solution(s: String): String {\n        return s\n    }\n}"
        val examples = listOf(ProblemExample("\"price: ${'$'}5\"", "\"price: ${'$'}5\""))

        val runner = KotlinRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "\\${'$'}5"
    }

    @Test
    fun `a main-style problem reaches the user's main through the top-level bridge`() {
        val code = "fun main(args: Array<String>) {\n" +
            "    val (a, b) = readLine()!!.split(' ').map(String::toInt)\n    println(a + b)\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val runner = KotlinRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "internal fun runnerInvokeSolutionMain() = main(emptyArray())"
        runner.source shouldContain "runnerInvokeSolutionMain()"
        runner.source shouldContain "check(1, \"4 5\", \"a = 4\\nb = 5\")"
    }

    /** The parameterless `fun main()` variant is called as it is declared. */
    @Test
    fun `a parameterless main is bridged without arguments`() {
        val code = "fun main() {\n    println(readLine())\n}"
        val examples = listOf(ProblemExample("\"x\"", "\"x\""))

        val runner = KotlinRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "internal fun runnerInvokeSolutionMain() = main()"
        runner.source shouldNotContain "main(emptyArray())"
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `no examples refuses with the reason`() {
        KotlinRunner.generate("class Solution { fun solution(a: Int): Int { return a } }", emptyList())
            .shouldBeInstanceOf<Runner.Refused>().reason shouldContain "no examples"
    }

    @Test
    fun `an arity mismatch refuses rather than truncating`() {
        val refused = KotlinRunner.generate(
            "class Solution { fun solution(a: Int): Int { return a } }",
            listOf(ProblemExample("1, 2, 3", "6")),
        ).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "3 argument"
    }

    @Test
    fun `an unmeasured parameter type refuses`() {
        KotlinRunner.generate(
            "class Solution { fun solution(xs: List<Int>): Int { return 0 } }",
            listOf(ProblemExample("[1]", "1")),
        ).shouldBeInstanceOf<Runner.Refused>()
    }

    /** Unlike C++, the harness compares through `Any?` — Java's null placeholder works here. */
    @Test
    fun `a missing expected value becomes a null placeholder like Java's`() {
        val runner = KotlinRunner.generate(
            "class Solution { fun solution(a: Int): Int { return a } }",
            listOf(ProblemExample("1", null)),
        ).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "null /* expected value not captured */"
    }

    /** Measured mains: `fun main(args: Array<String>)` and plain `fun main()` only. */
    @Test
    fun `a main with an unmeasured signature refuses`() {
        val code = "fun main(vararg xs: String) { println(xs.size) }"

        KotlinRunner.generate(code, listOf(ProblemExample("\"1\"", "\"1\"")))
            .shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `an unrecognised shape refuses`() {
        KotlinRunner.generate("val x = 1", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }
}
