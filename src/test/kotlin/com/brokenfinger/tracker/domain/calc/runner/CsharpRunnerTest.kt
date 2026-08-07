package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Text assertions for the C# generator; [CsharpRunnerExecutionTest] running the output
 * under a real `dotnet` is what earns the language its status (#88). C# is the first
 * two-artifact runner — a csproj rides along, and most of what matters lives in it: the
 * explicit compile list (default globbing would swallow every `.cs` under `attempts/`)
 * and the pinned entry point (the measured main-style skeleton has a `Main` of its own).
 */
class CsharpRunnerTest {
    @Test
    fun `a solution-style problem gets a runner and a project file`() {
        val code = "public class Solution {\n    public int solution(int num1, int num2) {\n" +
            "        return num1 * num2;\n    }\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val runner = CsharpRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.fileName shouldBe "runner_test.cs"
        runner.source shouldContain "Check(1, new Solution().solution(6, 7), 42);"
        runner.extras.map { it.fileName } shouldBe listOf("runner_test.csproj")
    }

    /** The csproj is where the two C# traps are disarmed. */
    @Test
    fun `the project file compiles exactly the two files and pins the entry point`() {
        val code = "public class Solution {\n    public int solution(int a) {\n        return a;\n    }\n}"

        val runner = CsharpRunner.generate(code, listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Generated>()

        val project = runner.extras.single().source
        project shouldContain "<EnableDefaultCompileItems>false</EnableDefaultCompileItems>"
        project shouldContain "<Compile Include=\"Solution.cs\" />"
        project shouldContain "<Compile Include=\"runner_test.cs\" />"
        project shouldContain "<StartupObject>RunnerTest</StartupObject>"
    }

    /** Measured 120860 spelling: the rectangular int[,], braces nested directly. */
    @Test
    fun `a grid parameter becomes a rectangular literal`() {
        val code = "public class Solution {\n    public int solution(int[,] dots) {\n        return 0;\n    }\n}"
        val examples = listOf(ProblemExample("[[1, 2], [3, 4]]", "10"))

        val runner = CsharpRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "new int[,] {{1, 2}, {3, 4}}"
    }

    @Test
    fun `arrays become typed constructions`() {
        val code = "public class Solution {\n    public int[] solution(int[] xs) {\n        return xs;\n    }\n}"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val runner = CsharpRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "new int[] {1, 2, 3}"
        runner.source shouldContain "new int[] {3, 2, 1}"
    }

    @Test
    fun `strings are quoted and escaped`() {
        val code = "public class Solution {\n    public string solution(string s) {\n        return s;\n    }\n}"
        val examples = listOf(ProblemExample("\"a \\\"quoted\\\" one\"", "\"a \\\"quoted\\\" one\""))

        val runner = CsharpRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "solution(\"a \\\"quoted\\\" one\")"
    }

    /** The measured skeleton's class is Example — the harness must call that, not Solution. */
    @Test
    fun `a main-style problem calls the class that holds Main`() {
        val code = """
            using System;

            public class Example
            {
                public static void Main()
                {
                    String[] s;

                    Console.Clear();
                    s = Console.ReadLine().Split(' ');

                    int a = Int32.Parse(s[0]);
                    int b = Int32.Parse(s[1]);

                    Console.WriteLine("{0}", a + b);
                }
            }
        """.trimIndent()
        val examples = listOf(ProblemExample("\"4 5\"", "\"9\""))

        val runner = CsharpRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "Example.Main();"
        runner.source shouldContain "Check(1, \"4 5\", \"9\");"
        runner.extras.single().source shouldContain "<StartupObject>RunnerTest</StartupObject>"
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `no examples refuses with the reason`() {
        CsharpRunner.generate("public class Solution { public int solution(int a) { return a; } }", emptyList())
            .shouldBeInstanceOf<Runner.Refused>().reason shouldContain "no examples"
    }

    @Test
    fun `an arity mismatch refuses rather than truncating`() {
        val refused = CsharpRunner.generate(
            "public class Solution { public int solution(int a) { return a; } }",
            listOf(ProblemExample("1, 2, 3", "6")),
        ).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "3 argument"
    }

    /** int[,] is rectangular by construction; a jagged value cannot fit it. */
    @Test
    fun `a jagged grid value refuses`() {
        CsharpRunner.generate(
            "public class Solution { public int solution(int[,] g) { return 0; } }",
            listOf(ProblemExample("[[1, 2], [3]]", "1")),
        ).shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `an unmeasured parameter type refuses`() {
        CsharpRunner.generate(
            "public class Solution { public int solution(int[][] g) { return 0; } }",
            listOf(ProblemExample("[[1]]", "1")),
        ).shouldBeInstanceOf<Runner.Refused>()
    }

    /** The harness compares through object — Java's null placeholder works here. */
    @Test
    fun `a missing expected value becomes a null placeholder like Java's`() {
        val runner = CsharpRunner.generate(
            "public class Solution { public int solution(int a) { return a; } }",
            listOf(ProblemExample("1", null)),
        ).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "null /* expected value not captured */"
    }

    @Test
    fun `an unrecognised shape refuses`() {
        CsharpRunner.generate("public class Helper {}", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }
}
