package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The test that earns Kotlin its "supported" status (#84): generated runners are compiled
 * by the **real Kotlin compiler** — `kotlin-compiler-embeddable` through the same
 * [CLITool] entry `kotlinc` itself uses, so the suite is hermetic and never skips — and
 * executed in a child `java` against measured examples.
 *
 * Two cases exist specifically to interrogate Kotlin:
 * - the array cases, because Kotlin arrays compare by reference with `==` and only a real
 *   run proves the harness dispatched to `contentEquals`/`contentDeepEquals`;
 * - the **two-example** main-style case, because `readLine()`'s internal LineReader could
 *   plausibly cache the first `System.in` — if it did, example 2 would fail here and the
 *   harness would need a subprocess fallback. This test is that experiment, pinned.
 */
class KotlinRunnerExecutionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    @Timeout(TIMEOUT)
    fun `a passing solution passes its generated runner`() {
        val solution = "class Solution {\n    fun solution(num1: Int, num2: Int): Int {\n" +
            "        return num1 * num2\n    }\n}"
        val examples = listOf(ProblemExample("6, 7", "42"), ProblemExample("11, 12", "132"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a wrong solution fails its generated runner with a non-zero exit`() {
        val solution = "class Solution {\n    fun solution(num1: Int, num2: Int): Int {\n" +
            "        return num1 + num2\n    }\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.output shouldContain "42"
        run.exitCode shouldBe 1
    }

    /** Kotlin arrays are reference-equal under ==; only a real run proves contentEquals won. */
    @Test
    @Timeout(TIMEOUT)
    fun `an IntArray-returning solution is compared by content`() {
        val solution = "class Solution {\n    fun solution(numbers: IntArray): IntArray {\n" +
            "        return numbers.reversedArray()\n    }\n}"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Measured 12950 shape — nested arrays through contentDeepEquals. */
    @Test
    @Timeout(TIMEOUT)
    fun `a nested array solution is compared deeply`() {
        val solution = "class Solution {\n" +
            "    fun solution(arr1: Array<IntArray>, arr2: Array<IntArray>): Array<IntArray> {\n" +
            "        return Array(arr1.size) { i ->\n" +
            "            IntArray(arr1[i].size) { j -> arr1[i][j] + arr2[i][j] }\n" +
            "        }\n    }\n}"
        val examples = listOf(ProblemExample("[[1, 2], [2, 3]], [[3, 4], [5, 6]]", "[[4, 6], [7, 9]]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /**
     * The measured main-style case (181951) with **two** examples on purpose: if
     * `readLine()` cached the first swapped `System.in`, the second example would read
     * stale input and fail — the in-process harness stands only while this passes.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution reads freshly swapped stdin on every example`() {
        val solution = "fun main(args: Array<String>) {\n" +
            "    val (a, b) = readLine()!!.split(' ').map(String::toInt)\n" +
            "    println(\"a = \" + a)\n    println(\"b = \" + b)\n}"
        val examples = listOf(
            ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""),
            ProblemExample("\"7 9\"", "\"a = 7\nb = 9\""),
        )

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution printing the wrong thing fails`() {
        val solution = "fun main(args: Array<String>) {\n    readLine()\n    println(\"nope\")\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    // Harness ------------------------------------------------------------------------------

    private data class Run(val exitCode: Int, val output: String)

    private fun execute(solutionSource: String, examples: List<ProblemExample>): Run {
        val runner = KotlinRunner.generate(solutionSource, examples).shouldBeInstanceOf<Runner.Generated>()
        Files.writeString(dir.resolve("Solution.kt"), solutionSource)
        Files.writeString(dir.resolve(runner.fileName), runner.source)
        compile(runner.fileName)
        val classpath = dir.resolve("out").toString() + File.pathSeparator + STDLIB
        return run("java", "-cp", classpath, "RunnerTest")
    }

    /**
     * [CLICompiler.doMainNoExit] is `kotlinc`'s own entry minus the `System.exit`;
     * compiler messages go to stderr, captured around the call. The stdlib on the compile
     * classpath is the one this test JVM is already running with.
     */
    private fun compile(fileName: String) {
        val messages = ByteArrayOutputStream()
        val originalErr = System.err
        val exit = try {
            System.setErr(PrintStream(messages))
            CLICompiler.doMainNoExit(
                K2JVMCompiler(),
                arrayOf(
                    dir.resolve("Solution.kt").toString(),
                    dir.resolve(fileName).toString(),
                    "-d", dir.resolve("out").toString(),
                    "-classpath", STDLIB,
                    "-no-stdlib", "-no-reflect", "-nowarn",
                ),
            )
        } finally {
            System.setErr(originalErr)
        }
        if (exit.code != 0) {
            error("the generated runner did not compile:\n$messages")
        }
    }

    private fun run(vararg command: String): Run {
        val process = ProcessBuilder(*command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(TIMEOUT, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            error("the command ${command.first()} hung; output so far:\n$output")
        }
        return Run(process.exitValue(), output)
    }

    private companion object {
        /** In-process compilation is the slow half on a cold JVM; generous beats flaky. */
        const val TIMEOUT = 120L

        /** The kotlin-stdlib jar this test JVM is already running with. */
        val STDLIB: String = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .first { Path.of(it).fileName.toString().startsWith("kotlin-stdlib") }
    }
}
