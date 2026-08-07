package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Text assertions for the C++ generator; [CppRunnerExecutionTest] compiling and running the
 * output with a real compiler is what earns the language its status (#80). C++ is the first
 * language where the harness shares one translation unit with the user's code (`#include
 * "Solution.cpp"`), so its file-scope identifiers wear a `runner_` prefix — a collision
 * would still fail loudly at compile time, never silently test the wrong thing.
 */
class CppRunnerTest {
    @Test
    fun `a solution-style problem gets a runner that passes named locals`() {
        val code = "int solution(int num1, int num2) {\n    return num1 * num2;\n}"
        val examples = listOf(ProblemExample("6, 7", "42"))

        val runner = CppRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.fileName shouldBe "runner_test.cpp"
        runner.source shouldContain "#include \"Solution.cpp\""
        // Locals, not inline temporaries — so reference-taking hand-edited signatures bind.
        runner.source shouldContain "int arg1 = 6;"
        runner.source shouldContain "int arg2 = 7;"
        runner.source shouldContain "int want = 42;"
        runner.source shouldContain "runner_check(1, solution(arg1, arg2), want);"
    }

    @Test
    fun `vectors become braced constructions of the declared type`() {
        val code = "vector<int> solution(vector<int> xs) { return xs; }"
        val examples = listOf(ProblemExample("[1, 2, 3]", "[3, 2, 1]"))

        val runner = CppRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "vector<int> arg1 = vector<int>{1, 2, 3};"
        runner.source shouldContain "vector<int> want = vector<int>{3, 2, 1};"
    }

    /** Measured 12950 shape: nested vectors, each level constructed explicitly. */
    @Test
    fun `nested vectors construct every level explicitly`() {
        val code = "vector<vector<int>> solution(vector<vector<int>> arr1, vector<vector<int>> arr2) {}"
        val examples = listOf(ProblemExample("[[1, 2], [2, 3]], [[3, 4], [5, 6]]", "[[4, 6], [7, 9]]"))

        val runner = CppRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "vector<vector<int>>{vector<int>{1, 2}, vector<int>{2, 3}}"
        runner.source shouldContain "vector<vector<int>>{vector<int>{4, 6}, vector<int>{7, 9}}"
    }

    @Test
    fun `long long literals carry the LL suffix`() {
        val code = "long long solution(long long n) { return n; }"
        val examples = listOf(ProblemExample("7", "7"))

        val runner = CppRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "long long arg1 = 7LL;"
    }

    @Test
    fun `strings are quoted and escaped`() {
        val code = "string solution(string s) { return s; }"
        val examples = listOf(ProblemExample("\"a \\\"quoted\\\" one\"", "\"a \\\"quoted\\\" one\""))

        val runner = CppRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "string arg1 = \"a \\\"quoted\\\" one\";"
    }

    @Test
    fun `a main-style problem gets a rename-and-include runner`() {
        val code = "#include <iostream>\nusing namespace std;\nint main(void) {\n" +
            "    int a;\n    int b;\n    cin >> a >> b;\n    cout << a + b << endl;\n    return 0;\n}"
        val examples = listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\""))

        val runner = CppRunner.generate(code, examples).shouldBeInstanceOf<Runner.Generated>()

        runner.source shouldContain "#define main runner_solution_main"
        runner.source shouldContain "#undef main"
        runner.source shouldContain "runner_check(1, \"4 5\", \"a = 4\\nb = 5\");"
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `no examples refuses with the reason`() {
        CppRunner.generate("int solution(int a) { return a; }", emptyList())
            .shouldBeInstanceOf<Runner.Refused>().reason shouldContain "no examples"
    }

    @Test
    fun `an arity mismatch refuses rather than truncating`() {
        val refused = CppRunner.generate(
            "int solution(int a) { return a; }",
            listOf(ProblemExample("1, 2, 3", "6")),
        ).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "3 argument"
    }

    @Test
    fun `a value that does not fit the declared type refuses`() {
        CppRunner.generate("int solution(int a) { return a; }", listOf(ProblemExample("\"text\"", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }

    @Test
    fun `an unmeasured parameter type refuses`() {
        CppRunner.generate("int solution(map<int, int> m) { return 0; }", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }

    /**
     * Java's runner can compare against a `null` placeholder and Python's against `None`;
     * C++ has no untyped placeholder that compiles, so a missing expected value refuses.
     */
    @Test
    fun `a missing expected value refuses instead of inventing a placeholder`() {
        val refused = CppRunner.generate(
            "int solution(int a) { return a; }",
            listOf(ProblemExample("1", null)),
        ).shouldBeInstanceOf<Runner.Refused>()

        refused.reason shouldContain "expected value"
    }

    /** The measured `main` is `int main(void)`; one taking `argc`/`argv` is unmeasured. */
    @Test
    fun `a main taking arguments refuses`() {
        val code = "int main(int argc, char** argv) { return 0; }"

        CppRunner.generate(code, listOf(ProblemExample("\"1\"", "\"1\"")))
            .shouldBeInstanceOf<Runner.Refused>().reason shouldContain "argument"
    }

    @Test
    fun `an unrecognised shape refuses`() {
        CppRunner.generate("void helper() {}", listOf(ProblemExample("1", "1")))
            .shouldBeInstanceOf<Runner.Refused>()
    }
}
