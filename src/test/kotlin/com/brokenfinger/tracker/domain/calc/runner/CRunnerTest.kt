package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Text assertions for the C generator; [CRunnerExecutionTest] compiling and running the
 * output with a real compiler is what earns the language its status (#86). The C-specific
 * facts under test: one wire value expands to two or three physical arguments, a 2-D value
 * must be staged as row arrays behind a pointer array (an `int[2][2]` cannot pass as
 * `int**`), and a returned `int*` is compared over the expected answer's length — the
 * judge's own convention, the length being implied by the answer.
 */
class CRunnerTest {
    @Test
    fun `a solution-style problem gets a runner with typed locals`() {
        val code = "int solution(int num1, int num2) {\n    return num1 * num2;\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val runner = CRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.fileName shouldBe "runner_test.c"
        runner.source shouldContain "#include \"Solution.c\""
        runner.source shouldContain "int arg1 = 6;"
        runner.source shouldContain "runner_check_int(1, solution(arg1, arg2), want);"
    }

    /** Measured 120817 shape: one wire array becomes the pointer and its length. */
    @Test
    fun `an array parameter expands to the array and its length`() {
        val code = "double solution(int numbers[], size_t numbers_len) { return 0; }"
        val examples = listOf(ProblemExample("[1, 2, 3]", "2.0"))

        val runner = CRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "int arg1[] = {1, 2, 3};"
        runner.source shouldContain "size_t arg1_len = 3;"
        runner.source shouldContain "solution(arg1, arg1_len)"
    }

    /** Measured 120860 shape: rows staged as arrays, gathered behind a pointer array. */
    @Test
    fun `a grid parameter is staged as row arrays behind a pointer array`() {
        val code = "int solution(int** dots, size_t dots_rows, size_t dots_cols) { return 0; }"
        val examples = listOf(ProblemExample("[[1, 2], [3, 4]]", "4"))

        val runner = CRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "int arg1_r0[] = {1, 2};"
        runner.source shouldContain "int arg1_r1[] = {3, 4};"
        runner.source shouldContain "int* arg1[] = {arg1_r0, arg1_r1};"
        runner.source shouldContain "size_t arg1_rows = 2;"
        runner.source shouldContain "size_t arg1_cols = 2;"
        runner.source shouldContain "solution(arg1, arg1_rows, arg1_cols)"
    }

    /** Measured 120821 shape: a returned int* is compared over the expected length. */
    @Test
    fun `an array return is compared over the expected answer's length`() {
        val code = "int* solution(int num_list[], size_t num_list_len) { return 0; }"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val runner = CRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "int want[] = {3, 2, 1};"
        runner.source shouldContain "runner_check_ints(1, solution(arg1, arg1_len), want, 3);"
    }

    /** Measured 120822 shape: const char* in, strcmp against the expected text. */
    @Test
    fun `a string problem passes and compares text`() {
        val code = "char* solution(const char* my_string) { return 0; }"
        val examples = listOf(ProblemExample("\"abc\"", "\"cba\""))

        val runner = CRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "const char* arg1 = \"abc\";"
        runner.source shouldContain "runner_check_str(1, solution(arg1), \"cba\");"
    }

    @Test
    fun `a main-style problem gets a rename-and-redirect runner`() {
        val code = "#include <stdio.h>\n\nint main(void) {\n    int a;\n    int b;\n" +
            "    scanf(\"%d %d\", &a, &b);\n    printf(\"%d\", a + b);\n    return 0;\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val runner = CRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "#define main runner_solution_main"
        runner.source shouldContain "#undef main"
        runner.source shouldContain "freopen"
        runner.source shouldContain "runner_check(1, \"4 5\", \"a = 4\\nb = 5\");"
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `no examples refuses with the reason`() {
        CRunner.generate("int solution(int a) { return a; }", emptyList())
            .shouldBeInstanceOf<Runner.Refused>().reason shouldContain "no examples"
    }

    /** Wire arity counts logical parameters — three values against one array is a mismatch. */
    @Test
    fun `an arity mismatch counts logical parameters`() {
        val refused = CRunner.generate(
            "double solution(int numbers[], size_t numbers_len) { return 0; }",
            listOf(ProblemExample("[1], [2], [3]", "2.0")),
        ).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "3 argument"
        refused.reason shouldContain "1"
    }

    /** The cols model is rectangular; a jagged grid cannot fit it. */
    @Test
    fun `a jagged grid refuses`() {
        CRunner.generate(
            "int solution(int** g, size_t g_rows, size_t g_cols) { return 0; }",
            listOf(ProblemExample("[[1, 2], [3]]", "1")),
        ).shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `an unmeasured signature refuses`() {
        CRunner.generate(
            "double solution(double xs[], size_t xs_len) { return 0; }",
            listOf(ProblemExample("[1.0]", "1.0")),
        ).shouldBeInstanceOf<Runner.Refused>()
    }

    /** Like C++: nothing untyped compiles, so a missing expected value refuses. */
    @Test
    fun `a missing expected value refuses instead of inventing a placeholder`() {
        CRunner.generate(
            "int solution(int a) { return a; }",
            listOf(ProblemExample("1", null)),
        ).shouldBeInstanceOf<Runner.Refused>().reason shouldContain "expected value"
    }

    /** The measured `main` is `int main(void)`; one taking `argc`/`argv` is unmeasured. */
    @Test
    fun `a main taking arguments refuses`() {
        CRunner.generate(
            "int main(int argc, char** argv) { return 0; }",
            listOf(ProblemExample("\"1\"", "\"1\"")),
        ).shouldBeInstanceOf<Runner.Refused>().reason shouldContain "argument"
    }

    @Test
    fun `an unrecognised shape refuses`() {
        CRunner.generate("void helper(void) {}", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }
}
