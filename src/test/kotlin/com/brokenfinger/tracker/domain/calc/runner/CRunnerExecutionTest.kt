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
 * The test that earns C its "supported" status (#86): generated runners are compiled by a
 * **real C compiler** and executed against measured examples. Every measured return kind
 * runs for real — scalar, `double`, malloc'd `char*`, malloc'd `int*` — plus the staging
 * moves that only a compiler can vet: the 1-D `arg, arg_len` expansion, the 2-D
 * pointer-array construction, and the main-style `freopen` redirection whose progress
 * lands on stderr. On windows-latest this is MinGW's turn to answer for `freopen`, the
 * way it answered for `\r\n` in Java's runner.
 *
 * Skips with a stated reason where no compiler is installed (JUnit assumption, dev rules
 * §6.5 posture). CI asserts the suite *ran* on its runners rather than assuming.
 */
class CRunnerExecutionTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun requiresCompiler() {
        assumeTrue(CC != null, "no C compiler (gcc/clang) on this machine — execution proof skipped")
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

    /** Measured 120817 shape: the wire's one array arrives as pointer plus length. */
    @Test
    @Timeout(TIMEOUT)
    fun `an array parameter arrives with its length`() {
        // The measured skeleton's own includes — they are what make size_t visible.
        val solution = "#include <stdio.h>\n#include <stdbool.h>\n#include <stdlib.h>\n\n" +
            "double solution(int numbers[], size_t numbers_len) {\n" +
            "    double sum = 0;\n" +
            "    for (size_t i = 0; i < numbers_len; i++) sum += numbers[i];\n" +
            "    return sum / numbers_len;\n}"
        val examples = listOf(ProblemExample("[1, 2, 3]", "2.0"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Measured 120821 shape: a malloc'd int* compared over the expected length. */
    @Test
    @Timeout(TIMEOUT)
    fun `a malloc returned array is compared over the expected length`() {
        val solution = "#include <stdlib.h>\n" +
            "int* solution(int num_list[], size_t num_list_len) {\n" +
            "    int* answer = (int*)malloc(num_list_len * sizeof(int));\n" +
            "    for (size_t i = 0; i < num_list_len; i++) answer[i] = num_list[num_list_len - 1 - i];\n" +
            "    return answer;\n}"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Measured 120860 shape: the staged pointer array really does convert to int**. */
    @Test
    @Timeout(TIMEOUT)
    fun `a grid parameter arrives as a pointer array with both sizes`() {
        val solution = "#include <stdio.h>\n#include <stdbool.h>\n#include <stdlib.h>\n\n" +
            "int solution(int** dots, size_t dots_rows, size_t dots_cols) {\n" +
            "    int sum = 0;\n" +
            "    for (size_t r = 0; r < dots_rows; r++)\n" +
            "        for (size_t c = 0; c < dots_cols; c++) sum += dots[r][c];\n" +
            "    return sum;\n}"
        val examples = listOf(ProblemExample("[[1, 2], [3, 4]]", "10"))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** Measured 120822 shape: const char* in, malloc'd char* out, strcmp verdict. */
    @Test
    @Timeout(TIMEOUT)
    fun `a string solution round-trips through strcmp`() {
        val solution = "#include <stdlib.h>\n#include <string.h>\n" +
            "char* solution(const char* my_string) {\n" +
            "    size_t n = strlen(my_string);\n" +
            "    char* answer = (char*)malloc(n + 1);\n" +
            "    for (size_t i = 0; i < n; i++) answer[i] = my_string[n - 1 - i];\n" +
            "    answer[n] = '\\0';\n    return answer;\n}"
        val examples = listOf(ProblemExample("\"abc\"", "\"cba\""))

        val run = execute(solution, examples)

        run.output shouldContain "ALL PASS"
        run.exitCode shouldBe 0
    }

    /** The measured main-style case (181951) through freopen'd streams, two examples. */
    @Test
    @Timeout(TIMEOUT)
    fun `a main-style solution is fed stdin and its stdout compared`() {
        val solution = "#include <stdio.h>\n\nint main(void) {\n    int a;\n    int b;\n" +
            "    scanf(\"%d %d\", &a, &b);\n" +
            "    printf(\"a = %d\\n\", a);\n    printf(\"b = %d\\n\", b);\n    return 0;\n}"
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
        val solution = "#include <stdio.h>\n\nint main(void) {\n    int a;\n" +
            "    scanf(\"%d\", &a);\n    printf(\"nope\\n\");\n    return 0;\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val run = execute(solution, examples)

        run.output shouldContain "FAIL"
        run.exitCode shouldBe 1
    }

    // Harness ------------------------------------------------------------------------------

    private data class Run(val exitCode: Int, val output: String)

    private fun execute(solutionSource: String, examples: List<ProblemExample>): Run {
        val runner = CRunner.generate(solutionSource, examples).shouldBeInstanceOf<Runner.Generated>()
        Files.writeString(dir.resolve("Solution.c"), solutionSource)
        Files.writeString(dir.resolve(runner.fileName), runner.source)
        compile(runner.fileName)
        return run(dir.resolve(BINARY).toString())
    }

    private fun compile(fileName: String) {
        val compiled = run(CC!!, fileName, "-o", BINARY)
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
        const val TIMEOUT = 120L

        const val BINARY = "runner_test.exe"

        /** The compiler, found once — `gcc` on the CI matrix (MinGW on windows), `clang` fallback. */
        val CC: String? = sequenceOf("gcc", "clang").firstOrNull { runs(it) }

        private fun runs(binary: String): Boolean = runCatching {
            val probe = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
        }.getOrDefault(false)
    }
}
