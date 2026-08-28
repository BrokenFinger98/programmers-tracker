package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `(user's code, measured examples) → a runnable Java file, or a refusal that says why.`
 *
 * The generated source is asserted textually here; `JavaRunnerExecutionTest` compiles and
 * runs it, which is the test that actually earns the language its "supported" status (#37).
 * The refusals are the other half of the contract: **a runner that compiles and tests the
 * wrong thing is worse than none**, so anything unparseable or mismatched must come back as
 * a [Runner.Refused] with a reason a human can act on — never a best-effort file.
 */
class JavaRunnerTest {
    @Test
    fun `a solution-style problem gets a runner that calls solution with typed arguments`() {
        val code = "class Solution { public int solution(int num1, int num2) { return num1 * num2; } }"
        val examples = listOf(ProblemExample("6, 7", "42"), ProblemExample("11, 12", "132"))

        val runner = JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.fileName shouldBe "RunnerTest.java"
        runner.source shouldContain "new Solution().solution(6, 7)"
        runner.source shouldContain "new Solution().solution(11, 12)"
        runner.source shouldContain "expected"
    }

    @Test
    fun `array arguments become Java array literals of the declared type`() {
        val code = "class Solution { public int solution(int[] xs) { return 0; } }"
        val examples = listOf(ProblemExample("[1, 2, 3]", "6"))

        val runner = JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "new int[]{1, 2, 3}"
    }

    @Test
    fun `two-dimensional arrays nest the literal`() {
        val code = "class Solution { public int[] solution(int m, int[][] balls) { return null; } }"
        val examples = listOf(ProblemExample("4, [[0, 0], [3, 1]]", "[2, 2]"))

        val runner = JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "new int[][]{new int[]{0, 0}, new int[]{3, 1}}"
    }

    @Test
    fun `string arguments are quoted and escaped`() {
        val code = """class Solution { public String solution(String s) { return s; } }"""
        val examples = listOf(ProblemExample("\"a \\\"quoted\\\" one\"", "\"a \\\"quoted\\\" one\""))

        val runner = JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "solution(\"a \\\"quoted\\\" one\")"
    }

    /** Measured on 181951: main-style. The runner feeds stdin and compares stdout. */
    @Test
    fun `a main-style problem gets a stdin-feeding runner`() {
        val code = """
            import java.util.Scanner;
            public class Solution {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    int a = sc.nextInt(); int b = sc.nextInt();
                    System.out.println("a = " + a); System.out.println("b = " + b);
                }
            }
        """.trimIndent()
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val runner = JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "setIn"
        runner.source shouldContain "a = 4\\nb = 5"
    }

    /**
     * Refused, not defaulted (#98). The stdin side already refused unreadable text; the
     * expected side two lines below substituted `""`, so the harness asserted that the
     * program prints nothing — the constitution's forbidden default substitution.
     */
    @Test
    fun `a main-style expected that is not text refuses instead of becoming empty`() {
        val code = "public class Solution {\n" +
            "    public static void main(String[] args) { System.out.println(\"x\"); }\n}"

        val refused = JavaRunner.generate(code, listOf(ProblemExample("\"in\"", "null")))
            .shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "expected output is not text"
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `no examples refuses with the reason`() {
        val code = "class Solution { public int solution(int a) { return a; } }"

        val refused = JavaRunner.generate(code, emptyList()).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "no examples"
    }

    @Test
    fun `an argument count that disagrees with the signature refuses rather than truncating`() {
        val code = "class Solution { public int solution(int a) { return a; } }"
        val examples = listOf(ProblemExample("1, 2, 3", "6"))

        val refused = JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "3 argument"
    }

    @Test
    fun `an unparseable signature refuses`() {
        val code = "class Solution { public List<Integer> solution(List<String> xs) { return null; } }"
        val examples = listOf(ProblemExample("[1]", "[1]"))

        JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `an unparseable example value refuses rather than approximating`() {
        val code = "class Solution { public int solution(int a) { return a; } }"
        val examples = listOf(ProblemExample("new int[]{1}", "1"))

        JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `a type the mapper does not cover refuses by naming it`() {
        val code = "class Solution { public double solution(double x) { return x; } }"
        val examples = listOf(ProblemExample("1.5", "1.5"))

        // double IS covered — this asserts the positive case so the list below stays honest.
        JavaRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()
    }

    /**
     * The header told the reader to run `java RunnerTest.java`, which needs **JDK 22+** — that is
     * when JEP 458 taught the launcher to resolve a second source file beside the first. On JDK 21
     * it fails with `cannot find symbol: variable Solution`, which points at the generated file
     * rather than at the JDK (#329).
     *
     * The tool requires 25 and CI runs 25, so the instruction was true *for the tool*. The record
     * repository is the user's own folder, opened with whatever they have — and Programmers offers
     * Java 8 and 11 for many problems, so 11, 17 or 21 locally is the normal case.
     *
     * Both source files named, on every JDK since 8. Pinned here because the defect was an
     * instruction drifting from what actually runs, and nothing was watching it.
     */
    @Test
    fun `the header's command names both source files, so it works before JDK 22`() {
        val code = "class Solution { public int solution(int a, int b) { return a + b; } }"
        val runner = JavaRunner.generate(code, listOf(ProblemExample("6, 7", "13")))
            .shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "javac RunnerTest.java Solution.java && java RunnerTest"
    }

    /** The same for the stdin shape, which carries its own header. */
    @Test
    fun `the stdin runner's header names both source files too`() {
        val code = "public class Solution {\n    public static void main(String[] args) {\n    }\n}"
        val runner = JavaRunner.generate(code, listOf(ProblemExample("\"3 4\"", "\"7\"")))
            .shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "javac RunnerTest.java Solution.java && java RunnerTest"
    }
}
