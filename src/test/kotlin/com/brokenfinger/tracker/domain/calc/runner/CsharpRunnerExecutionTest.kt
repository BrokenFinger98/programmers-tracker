package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The test that earns C# its "supported" status (#88): generated runner projects are built
 * and executed by a **real `dotnet`** against measured examples. Two questions only a real
 * run can answer live here — whether the two-artifact project actually builds with its
 * explicit compile list, and what the measured skeleton's own `Console.Clear()` does under
 * redirected output on each OS. The main-style fixture keeps the `Clear()` in on purpose.
 *
 * Skips with a stated reason where `dotnet` genuinely cannot run — which includes the dev
 * machine this was written on (an x86_64 dotnet host on an arm64 mac), so **CI's three
 * runners are this suite's proof-bearer**; the proof-ran gate holds it to that. Dev rules
 * §6.5 posture.
 */
class CsharpRunnerExecutionTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun requiresDotnet() {
        assumeTrue(DOTNET, "dotnet cannot run on this machine — execution proof skipped (CI carries it)")
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a passing solution passes its generated runner`() {
        val solution = "public class Solution {\n    public int solution(int num1, int num2) {\n" +
            "        return num1 * num2;\n    }\n}"
        val examples = listOf(ProblemExample("6, 7", "42"), ProblemExample("11, 12", "132"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a wrong solution fails its generated runner with a non-zero exit`() {
        val solution = "public class Solution {\n    public int solution(int num1, int num2) {\n" +
            "        return num1 + num2;\n    }\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.output shouldContain "42"
        run.exitCode shouldBe 1
    }

    /** Arrays are reference-equal under Equals; only a real run proves SequenceEqual won. */
    @Test
    @Timeout(TIMEOUT)
    fun `an array-returning solution is compared by content`() {
        val solution = "using System.Linq;\n\npublic class Solution {\n" +
            "    public int[] solution(int[] xs) {\n        return xs.Reverse().ToArray();\n    }\n}"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Measured 120860 shape — the rectangular int[,] literal and its content comparison. */
    @Test
    @Timeout(TIMEOUT)
    fun `a grid parameter arrives as a rectangular array`() {
        val solution = "public class Solution {\n    public int solution(int[,] dots) {\n" +
            "        int sum = 0;\n" +
            "        for (int r = 0; r < dots.GetLength(0); r++)\n" +
            "            for (int c = 0; c < dots.GetLength(1); c++) sum += dots[r, c];\n" +
            "        return sum;\n    }\n}"
        val examples = listOf(ProblemExample("[[1, 2], [3, 4]]", "10"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Measured 120822 shape. */
    @Test
    @Timeout(TIMEOUT)
    fun `a string solution round-trips quoting and escaping`() {
        val solution = "public class Solution {\n    public string solution(string s) {\n" +
            "        char[] chars = s.ToCharArray();\n        System.Array.Reverse(chars);\n" +
            "        return new string(chars);\n    }\n}"
        val examples = listOf(ProblemExample("\"abc\"", "\"cba\""))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** The universal main-style path — no `Console.Clear()`, must pass on every OS. */
    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution is fed stdin through the swapped console`() {
        val solution = "using System;\n\npublic class Example\n{\n    public static void Main()\n    {\n" +
            "        String[] s = Console.ReadLine().Split(' ');\n" +
            "        Console.WriteLine(\"a = {0}\", Int32.Parse(s[0]));\n" +
            "        Console.WriteLine(\"b = {0}\", Int32.Parse(s[1]));\n    }\n}"
        val examples = listOf(
            ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""),
            ProblemExample("\"7 9\"", "\"a = 7\nb = 9\""),
        )

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /**
     * The measured skeleton — `Console.Clear()` kept in on purpose. Measured in CI
     * 2026-08-07: macOS/Linux no-op it under redirection; **Windows throws**, so there the
     * harness must state its skip instead of crashing on the first example. Both measured
     * behaviours are pinned here, per OS.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `the skeleton's Clear no-ops on unix and skips with a reason on windows`() {
        val solution = """
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

                    Console.WriteLine("a = {0}", a);
                    Console.WriteLine("b = {0}", b);
                }
            }
        """.trimIndent()
        val examples = listOf(
            ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""),
            ProblemExample("\"7 9\"", "\"a = 7\nb = 9\""),
        )

        val run = execute(solution, examples)

        run.output shouldContain if (WINDOWS) "SKIPPED" else "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution printing the wrong thing fails`() {
        val solution = "using System;\n\npublic class Example\n{\n    public static void Main()\n    {\n" +
            "        Console.ReadLine();\n        Console.WriteLine(\"nope\");\n    }\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    // Harness ------------------------------------------------------------------------------

    private data class Run(val exitCode: Int, val output: String)

    private fun execute(solutionSource: String, examples: List<ProblemExample>): Run {
        val runner = CsharpRunner.generate(solutionSource, examples).shouldBeInstanceOf<Runner.Generated>()
        Files.writeString(dir.resolve("Solution.cs"), solutionSource)
        Files.writeString(dir.resolve(runner.fileName), runner.source)
        runner.extras.forEach { Files.writeString(dir.resolve(it.fileName), it.source) }

        val process = ProcessBuilder("dotnet", "run", "--project", "runner_test.csproj")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1"
                environment()["DOTNET_NOLOGO"] = "1"
                environment()["DOTNET_SKIP_FIRST_TIME_EXPERIENCE"] = "1"
            }
            .start()
        val finished = process.waitFor(TIMEOUT, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            error("dotnet run hung; output so far:\n$output")
        }
        return Run(process.exitValue(), output)
    }

    private companion object {
        /** Every case pays a project build; the first also warms the SDK on a cold runner. */
        const val TIMEOUT = 300L

        /** Which measured `Console.Clear()` behaviour to expect — it differs by OS. */
        val WINDOWS: Boolean = System.getProperty("os.name").startsWith("Windows")

        /** Probed once — false also on machines whose installed dotnet host cannot start. */
        val DOTNET: Boolean = runCatching {
            val probe = ProcessBuilder("dotnet", "--version").redirectErrorStream(true).start()
            probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0
        }.getOrDefault(false)
    }
}
