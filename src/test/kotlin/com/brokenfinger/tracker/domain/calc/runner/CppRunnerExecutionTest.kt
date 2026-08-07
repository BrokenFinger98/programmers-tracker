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
 * The test that earns C++ its "supported" status (#80): generated runners are compiled by a
 * **real C++ compiler** and executed against measured examples, reproducing the judge's
 * verdict. C++ raises the stakes over Python's suite — the emitted source must not merely be
 * valid, it must *compile in one translation unit with the user's code*, and the harness
 * templates (`runner_str` overloads, `runner_check` deduction) only prove themselves at
 * instantiation. That is why the by-value, nested and string cases each run for real: every
 * one instantiates a different overload set.
 *
 * Skips with a stated reason where no compiler is installed (JUnit assumption, dev rules
 * §6.5 posture). CI asserts the suite *ran* on its runners rather than assuming — a skip
 * there would silently un-earn the status this test exists to grant.
 */
class CppRunnerExecutionTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun requiresCompiler() {
        assumeTrue(CXX != null, "no C++ compiler (g++/clang++) on this machine — execution proof skipped")
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a passing solution passes its generated runner`() {
        val solution = "int solution(int num1, int num2) {\n    return num1 * num2;\n}"
        val examples = listOf(ProblemExample("6, 7", "42"), ProblemExample("11, 12", "132"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a wrong solution fails its generated runner with a non-zero exit`() {
        val solution = "int solution(int num1, int num2) {\n    return num1 + num2;\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.output shouldContain "42"
        run.exitCode shouldBe 1
    }

    /** `std::vector` compares by value, which is exactly what the judge's comparison does. */
    @Test
    @Timeout(TIMEOUT)
    fun `a vector-returning solution is compared by value`() {
        val solution = "#include <vector>\nusing namespace std;\n" +
            "vector<int> solution(vector<int> xs) {\n    return vector<int>(xs.rbegin(), xs.rend());\n}"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Measured 12950 shape — instantiates the nested `runner_str`/`==` templates for real. */
    @Test
    @Timeout(TIMEOUT)
    fun `a nested vector solution compiles its harness templates and passes`() {
        val solution = "#include <vector>\nusing namespace std;\n" +
            "vector<vector<int>> solution(vector<vector<int>> arr1, vector<vector<int>> arr2) {\n" +
            "    for (size_t i = 0; i < arr1.size(); ++i)\n" +
            "        for (size_t j = 0; j < arr1[i].size(); ++j) arr1[i][j] += arr2[i][j];\n" +
            "    return arr1;\n}"
        val examples = listOf(ProblemExample("[[1, 2], [2, 3]], [[3, 4], [5, 6]]", "[[4, 6], [7, 9]]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Instantiates the `std::string` overload — the third distinct `runner_str` resolution. */
    @Test
    @Timeout(TIMEOUT)
    fun `a string-returning solution round-trips quoting and escaping`() {
        val solution = "#include <string>\nusing namespace std;\n" +
            "string solution(string s) {\n    return s;\n}"
        val examples = listOf(ProblemExample("\"a \\\"quoted\\\" one\"", "\"a \\\"quoted\\\" one\""))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** The measured main-style case (181951), through the in-process stream swap. */
    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution is fed stdin and its stdout compared`() {
        val solution = "#include <iostream>\nusing namespace std;\n" +
            "int main(void) {\n    int a;\n    int b;\n    cin >> a >> b;\n" +
            "    cout << \"a = \" << a << \"\\n\";\n    cout << \"b = \" << b << \"\\n\";\n    return 0;\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution printing the wrong thing fails`() {
        // Reads the input like a real wrong answer would; the eof state it leaves behind is
        // exactly what runner_check's cin.clear() exists to reset.
        val solution = "#include <iostream>\nusing namespace std;\n" +
            "int main(void) {\n    int a;\n    int b;\n    cin >> a >> b;\n" +
            "    cout << \"nope\\n\";\n    return 0;\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    // Harness ------------------------------------------------------------------------------

    private data class Run(val exitCode: Int, val output: String)

    private fun execute(solutionSource: String, examples: List<ProblemExample>): Run {
        val runner = CppRunner.generate(solutionSource, examples).shouldBeInstanceOf<Runner.Generated>()
        Files.writeString(dir.resolve("Solution.cpp"), solutionSource)
        Files.writeString(dir.resolve(runner.fileName), runner.source)
        compile(runner.fileName)
        return run(dir.resolve(BINARY).toString())
    }

    /** The explicit `.exe` suffix is what MinGW produces anyway and is harmless elsewhere. */
    private fun compile(fileName: String) {
        val compiled = run(CXX!!, "-std=c++17", fileName, "-o", BINARY)
        if (compiled.exitCode != 0) {
            error("the generated runner did not compile:\n${compiled.output}")
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
        /** Compiling is the slow half on a cold CI runner; generous beats flaky. */
        const val TIMEOUT = 120L

        const val BINARY = "runner_test.exe"

        /**
         * The compiler, found once. `g++` everywhere the CI matrix runs (MinGW provides it
         * on windows-latest); `clang++` as the fallback spelling — on macOS `g++` is that
         * same compiler under its compatibility name.
         */
        val CXX: String? = sequenceOf("g++", "clang++").firstOrNull { runs(it) }

        private fun runs(binary: String): Boolean = runCatching {
            val probe = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
        }.getOrDefault(false)
    }
}
