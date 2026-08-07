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
 * The test that earns JavaScript its "supported" status (#82): generated runners are
 * executed by a **real `node`** against measured examples and must reproduce the judge's
 * verdict. The script-loading move (`vm.runInThisContext` making the un-exported
 * declaration visible) only proves itself by actually running.
 *
 * Skips with a stated reason where `node` genuinely is not installed (JUnit assumption,
 * dev rules §6.5 posture). CI asserts the suite *ran* on its runners rather than assuming —
 * a skip there would silently un-earn the status this test exists to grant.
 */
class JavascriptRunnerExecutionTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun requiresNode() {
        assumeTrue(NODE != null, "node is not installed on this machine — execution proof skipped")
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a passing solution passes its generated runner`() {
        val solution = "function solution(num1, num2) {\n    return num1 * num2;\n}"
        val examples = listOf(ProblemExample("6, 7", "42"), ProblemExample("11, 12", "132"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a wrong solution fails its generated runner with a non-zero exit`() {
        val solution = "function solution(num1, num2) {\n    return num1 + num2;\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.output shouldContain "42"
        run.exitCode shouldBe 1
    }

    /** Arrays compare via JSON.stringify — deep and order-sensitive, like the judge. */
    @Test
    @Timeout(TIMEOUT)
    fun `an array-returning solution is compared by value`() {
        val solution = "function solution(xs) {\n    return xs.slice().reverse();\n}"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** The measured main-style case (181951), through a real stdin pipe and readline. */
    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution is fed stdin and its stdout compared`() {
        val solution = "const readline = require('readline');\n" +
            "const rl = readline.createInterface({ input: process.stdin });\n" +
            "let input = [];\n" +
            "rl.on('line', function (line) {\n    input = line.split(' ');\n" +
            "}).on('close', function () {\n" +
            "    console.log('a = ' + input[0]);\n    console.log('b = ' + input[1]);\n});"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution printing the wrong thing fails`() {
        // Reads stdin like a real wrong answer would — a script with no stdin signal at all
        // has no shape and is refused at generation, which its own test covers.
        val solution = "process.stdin.resume();\n" +
            "process.stdin.on('data', function () {});\n" +
            "process.stdin.on('end', function () { console.log('nope'); });"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    // Harness ------------------------------------------------------------------------------

    private data class Run(val exitCode: Int, val output: String)

    private fun execute(solutionSource: String, examples: List<ProblemExample>): Run {
        val runner = JavascriptRunner.generate(solutionSource, examples).shouldBeInstanceOf<Runner.Generated>()
        Files.writeString(dir.resolve("Solution.js"), solutionSource)
        Files.writeString(dir.resolve(runner.fileName), runner.source)

        val process = ProcessBuilder(NODE, runner.fileName)
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

        /** The interpreter, found once — `node` on every platform the CI matrix runs. */
        val NODE: String? = sequenceOf("node").firstOrNull { runs(it) }

        private fun runs(binary: String): Boolean = runCatching {
            val probe = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
        }.getOrDefault(false)
    }
}
