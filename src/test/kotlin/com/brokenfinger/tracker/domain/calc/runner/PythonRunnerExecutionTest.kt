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
 * The test that earns Python its "supported" status (#78): generated runners are executed by
 * a **real `python3`** against measured examples and must reproduce the judge's verdict —
 * the first language where the runner is not the build's own, so nothing short of running it
 * proves the emitted source is valid Python at all.
 *
 * Skips with a stated reason where `python3` genuinely is not installed (JUnit assumption,
 * dev rules §6.5 posture). CI asserts the suite *ran* on its runners rather than assuming —
 * a skip there would silently un-earn the status this test exists to grant.
 */
class PythonRunnerExecutionTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun requiresPython() {
        assumeTrue(PYTHON != null, "python3 is not installed on this machine — execution proof skipped")
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a passing solution passes its generated runner`() {
        val solution = "def solution(num1, num2):\n    return num1 * num2\n"
        val examples = listOf(ProblemExample("6, 7", "42"), ProblemExample("11, 12", "132"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a wrong solution fails its generated runner with a non-zero exit`() {
        val solution = "def solution(num1, num2):\n    return num1 + num2\n"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.output shouldContain "42"
        run.exitCode shouldBe 1
    }

    /** Lists compare by value in Python, which is exactly what the judge's == does. */
    @Test
    @Timeout(TIMEOUT)
    fun `a list-returning solution is compared by value`() {
        val solution = "def solution(xs):\n    return xs[::-1]\n"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** The measured main-style case (181951), through a real stdin pipe this time. */
    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution is fed stdin and its stdout compared`() {
        val solution = "a, b = map(int, input().split())\nprint(f\"a = {a}\")\nprint(f\"b = {b}\")\n"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution printing the wrong thing fails`() {
        // Reads the input like a real wrong answer would — a script with no input() at all
        // has no shape signal and is refused at generation, which its own test covers.
        val solution = "input()\nprint(\"nope\")\n"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    /**
     * A crash after the right output is still a crash (#98). The harness read only stdout,
     * so a solution that printed both correct lines and then died reported ALL PASS with
     * exit 0 — reproduced before the fix. The judge sees `exitCode` on the run/testcase
     * frame (protocol §7.1), so it would not have been fooled.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `a solution that prints the right answer and then crashes fails`() {
        val solution = "import sys\n" +
            "a, b = map(int, input().split())\n" +
            "print(f\"a = {a}\")\nprint(f\"b = {b}\")\n" +
            "raise IndexError('after the answer')"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "exited 1"
        run.exitCode shouldBe 1
    }

    // Harness ------------------------------------------------------------------------------

    private data class Run(val exitCode: Int, val output: String)

    private fun execute(solutionSource: String, examples: List<ProblemExample>): Run {
        val runner = PythonRunner.generate(solutionSource, examples).shouldBeInstanceOf<Runner.Generated>()
        Files.writeString(dir.resolve("Solution.py"), solutionSource)
        Files.writeString(dir.resolve(runner.fileName), runner.source)

        val process = ProcessBuilder(PYTHON, runner.fileName)
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

    private companion object {
        const val TIMEOUT = 60L

        /**
         * The interpreter, found once. `python3` on unixy systems; Windows installs expose
         * `python` — both are tried, and neither being present skips the suite.
         */
        val PYTHON: String? = sequenceOf("python3", "python").firstOrNull { runs(it) }

        private fun runs(binary: String): Boolean = runCatching {
            val probe = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
        }.getOrDefault(false)
    }
}
