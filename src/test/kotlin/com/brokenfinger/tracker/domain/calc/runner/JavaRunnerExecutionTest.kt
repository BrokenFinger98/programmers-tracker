package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The test that earns Java its "supported" status (#37): the generated runner is **compiled
 * and executed** against the measured examples, in a real child JVM, and must reproduce the
 * judge's verdict. Text assertions prove the generator wrote what we meant; only running it
 * proves what we meant is right — the single-file source launcher, the stdin swap, the
 * deep-equality on arrays are all claims about `java`, not about our strings.
 *
 * Runs on the JDK the build already requires. `java <file>.java` (JEP 330) compiles in
 * memory, so no javac orchestration and no scaffolding — which is itself part of the
 * contract: the runner must work in a bare records directory.
 */
class JavaRunnerExecutionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    @Timeout(TIMEOUT)
    fun `a passing solution passes its generated runner`() {
        val solution = "public class Solution { public int solution(int num1, int num2) { return num1 * num2; } }"
        val examples = listOf(ProblemExample("6, 7", "42"), ProblemExample("11, 12", "132"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** The runner's whole point: a wrong solution must FAIL, and say on which example. */
    @Test
    @Timeout(TIMEOUT)
    fun `a wrong solution fails its generated runner with a non-zero exit`() {
        val solution = "public class Solution { public int solution(int num1, int num2) { return num1 + num2; } }"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.output shouldContain "42"
        run.exitCode shouldBe 1
    }

    /**
     * The defect this suite existed to catch and did not (#90): the harness used to OR a
     * rendered-string comparison onto `deepEquals`, and `Arrays.deepToString` is not
     * injective — a one-element `{"a, b"}` and a two-element `{"a", "b"}` both render
     * `[a, b]`, so a wrong-shaped answer passed. Runs for real, because the old code
     * printed `ALL PASS` here.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `an answer of the wrong shape fails even when it renders identically`() {
        val solution = """
            public class Solution {
                public String[] solution(String s) {
                    return new String[]{"a, b"};
                }
            }
        """.trimIndent()
        val examples = listOf(ProblemExample("\"x\"", "[\"a\", \"b\"]"))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    @Test
    @Timeout(TIMEOUT)
    fun `an array-returning solution is compared by content, not by reference`() {
        val solution = """
            public class Solution {
                public int[] solution(int[] xs) {
                    int[] out = new int[xs.length];
                    for (int i = 0; i < xs.length; i++) out[i] = xs[xs.length - 1 - i];
                    return out;
                }
            }
        """.trimIndent()
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** The measured main-style problem (181951), end to end: stdin in, stdout compared. */
    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution is fed stdin and its stdout compared`() {
        val solution = """
            import java.util.Scanner;
            public class Solution {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    int a = sc.nextInt(); int b = sc.nextInt();
                    System.out.println("a = " + a);
                    System.out.println("b = " + b);
                }
            }
        """.trimIndent()
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution printing the wrong thing fails`() {
        val solution = """
            public class Solution {
                public static void main(String[] args) { System.out.println("nope"); }
            }
        """.trimIndent()
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    // Harness ------------------------------------------------------------------------------

    private data class Run(val exitCode: Int, val output: String)

    private fun execute(solutionSource: String, examples: List<ProblemExample>): Run {
        val runner = JavaRunner.generate(solutionSource, examples).shouldBeInstanceOf<Runner.Generated>()
        Files.writeString(dir.resolve("Solution.java"), solutionSource)
        Files.writeString(dir.resolve(runner.fileName), runner.source)

        val process = ProcessBuilder(javaBinary(), runner.fileName)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(TIMEOUT, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            error("the generated runner hung; output so far:\n$output")
        }
        return Run(process.exitValue(), output)
    }

    // The running JVM's own launcher — carries the `.exe` on Windows (same trick as LockHolder).
    private fun javaBinary(): String = ProcessHandle.current().info().command().orElseGet {
        Path.of(System.getProperty("java.home"), "bin", "java").toString()
    }

    private companion object {
        /** A child JVM start plus an in-memory compile; anything longer has hung. */
        const val TIMEOUT = 60L
    }
}
