package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The value table for C — the language the other tables could not reach (#302).
 *
 * `RunnerValueTableTest` is shaped around *one declared type, one wire value*, and in C that is
 * false: a 1-D array arrives as `int n[], size_t n_len` and a grid as
 * `int** n, size_t n_rows, size_t n_cols`. One wire value, three parameters. A table keyed on a
 * single type cannot express that, so C sat at 47 uncovered branches with every other language
 * tabled.
 *
 * `CRunnerTest` already covers the shapes that *work* — the array pair, the grid triple, the
 * `int*` return. **What is here is the other half: every value each declared type must refuse.**
 * That is where the branches were, and it is the half that matters — a literal rendered wrong
 * produces a file that either fails to compile (noise) or compiles and tests something other than
 * the problem, which is the silent-wrong-data failure the constitution ranks worst.
 *
 * C makes that worse than the other languages do, because a wrong length is not a wrong answer but
 * a read past the end of a block.
 */
class CRunnerValueTest {
    // Literals ----------------------------------------------------------------------------------

    /** One arm of `scalarLiteral` per measured skeleton type, and the suffix each one needs. */
    @TestFactory
    fun `each scalar type renders the literal C needs`() = listOf(
        Rendered("int n", "7", "int arg1 = 7;"),
        Rendered("long long n", "7", "long long arg1 = 7LL;"),
        Rendered("double n", "1.5", "double arg1 = 1.5;"),
        Rendered("bool n", "true", "bool arg1 = true;"),
        Rendered("bool n", "false", "bool arg1 = false;"),
        // An integer where a double is declared widens rather than refusing: the value is
        // unambiguously that number and C would have widened it at the call anyway.
        Rendered("double n", "7", "double arg1 = 7.0;"),
        Rendered("const char* n", "\"ab\"", """const char* arg1 = "ab";"""),
    ).map { it.asTest() }

    /**
     * Escaping is the whole job of `quoted`, and every one of these ends the string literal early
     * if it is passed through — a generated file that does not compile, from an example the judge
     * handed us verbatim.
     */
    @TestFactory
    fun `a text literal escapes what would end the string early`() = listOf(
        Rendered("const char* n", "\"a\\\"b\"", """const char* arg1 = "a\"b";"""),
        Rendered("const char* n", "\"a\\\\b\"", """const char* arg1 = "a\\b";"""),
        Rendered("const char* n", "\"a\\nb\"", """const char* arg1 = "a\nb";"""),
        Rendered("const char* n", "\"a\\tb\"", """const char* arg1 = "a\tb";"""),
        Rendered("const char* n", "\"a\\rb\"", """const char* arg1 = "a\rb";"""),
    ).map { it.asTest() }

    // Returns -----------------------------------------------------------------------------------

    /** One `checkFor` arm per measured return kind — each has its own comparison helper. */
    @TestFactory
    fun `each return type picks the check C can make for it`() = listOf(
        Returned("int", "0", "int want = 0;", "runner_check_int(1, solution(arg1), want);"),
        Returned("long long", "0", "long long want = 0LL;", "runner_check_ll(1, solution(arg1), want);"),
        Returned("double", "1.5", "double want = 1.5;", "runner_check_double(1, solution(arg1), want);"),
        Returned("bool", "true", "bool want = true;", "runner_check_bool(1, solution(arg1), want);"),
        Returned("char*", "\"ab\"", """runner_check_str(1, solution(arg1), "ab");""", "runner_check_str"),
        Returned("int*", "[1, 2]", "int want[] = {1, 2};", "runner_check_ints(1, solution(arg1), want, 2);"),
        // C forbids empty braces before C23, so an empty answer is a one-slot array with length 0 —
        // the length is what the check reads, and 0 elements compare as trivially equal.
        Returned("int*", "[]", "int want[] = {0};", "runner_check_ints(1, solution(arg1), want, 0);"),
    ).map { it.asTest() }

    // Refusals ----------------------------------------------------------------------------------

    /**
     * A value the declared type cannot hold. Every one comes back [Runner.Refused] with a reason —
     * never a best-effort file, and never a coerced value.
     */
    @TestFactory
    fun `a value the declared type cannot hold is refused`() = listOf(
        // C's int is 32 bits and Programmers' examples are not bounded by them.
        Refusal("int n", "3000000000", "an int that does not fit 32 bits"),
        Refusal("int n", "1.5", "a double where an int was declared"),
        Refusal("int n", "\"ab\"", "text where an int was declared"),
        Refusal("long long n", "\"ab\"", "text where a long long was declared"),
        Refusal("double n", "\"ab\"", "text where a double was declared"),
        Refusal("bool n", "1", "a number where a bool was declared"),
        Refusal("const char* n", "7", "a number where text was declared"),
        Refusal("const char* n", "[1, 2]", "an array where text was declared"),
        Refusal("int n", "[1, 2]", "an array where a scalar was declared"),
    ).map { it.asTest() }

    /**
     * The array pair. `arrayLocals` writes both the block and its `_len`, so a value it cannot
     * read has to refuse **both** — a length emitted beside a block that was never staged is how
     * a C runner reads past the end of memory.
     */
    @TestFactory
    fun `an array parameter refuses a value it cannot size`() = listOf(
        Refusal(ARRAY_PARAM, "7", "a scalar where an array was declared"),
        Refusal(ARRAY_PARAM, "\"ab\"", "text where an array was declared"),
        Refusal(ARRAY_PARAM, "[\"a\"]", "a text element in an int array"),
        Refusal(ARRAY_PARAM, "[1.5]", "a fractional element in an int array"),
        Refusal(ARRAY_PARAM, "[3000000000]", "an element that does not fit 32 bits"),
        Refusal(ARRAY_PARAM, "[[1]]", "a nested array where ints were declared"),
    ).map { it.asTest() }

    /**
     * The grid triple, where the refusals carry the most weight: `_rows` and `_cols` are written
     * once from the first row, so a **ragged** value would hand the solution a `cols` that is a
     * lie for every row after the first.
     */
    @TestFactory
    fun `a grid parameter refuses anything that is not a rectangle`() = listOf(
        Refusal(GRID_PARAM, "[[1, 2], [3]]", "ragged rows — cols would be a lie after the first"),
        Refusal(GRID_PARAM, "[]", "no rows at all"),
        Refusal(GRID_PARAM, "[[]]", "a row with no columns"),
        Refusal(GRID_PARAM, "[1, 2]", "a flat array where a grid was declared"),
        Refusal(GRID_PARAM, "[[\"a\"]]", "a text element in an int grid"),
        Refusal(GRID_PARAM, "7", "a scalar where a grid was declared"),
    ).map { it.asTest() }

    /** The return side of the same rule — an expected answer the declared return cannot be. */
    @TestFactory
    fun `an expected answer the return type cannot be is refused`() = listOf(
        ReturnRefusal("char*", "7", "a number where text is returned"),
        ReturnRefusal("char*", "[1]", "an array where text is returned"),
        ReturnRefusal("int*", "\"ab\"", "text where an int array is returned"),
        ReturnRefusal("int*", "7", "a scalar where an int array is returned"),
        ReturnRefusal("int*", "[\"a\"]", "a text element in a returned int array"),
        ReturnRefusal("int", "\"ab\"", "text where an int is returned"),
        ReturnRefusal("int", "[1]", "an array where an int is returned"),
    ).map { it.asTest() }

    // The example itself, rather than its values -------------------------------------------------

    @TestFactory
    fun `an example that does not fit the declaration is refused with the reason`() = listOf(
        Detail(
            why = "more wire values than declared parameters",
            code = skeleton("int", "int n"),
            example = ProblemExample("1, 2", "0"),
            says = "has 2 argument(s) but solution declares 1",
        ),
        Detail(
            why = "an array's size counted as a wire value",
            code = skeleton("int", ARRAY_PARAM),
            example = ProblemExample("[1, 2], 2", "0"),
            says = "arrays count once",
        ),
        Detail(
            why = "an input that is not an argument list at all",
            code = skeleton("int", "int n"),
            example = ProblemExample("{", "0"),
            says = "could not be parsed as an argument list",
        ),
        Detail(
            why = "no expected value captured",
            code = skeleton("int", "int n"),
            example = ProblemExample("7", null),
            says = "expected value was not captured",
        ),
        Detail(
            why = "no input captured",
            code = skeleton("int", "int n"),
            example = ProblemExample(null, "0"),
            says = "could not be parsed as an argument list",
        ),
    ).map { case ->
        DynamicTest.dynamicTest(case.why) {
            CRunner.generate(case.code, listOf(case.example))
                .shouldBeInstanceOf<Runner.Refused>()
                .reason shouldContain case.says
        }
    }

    /** Nothing to generate from, and the reason says what to press rather than naming a defect. */
    @org.junit.jupiter.api.Test
    fun `no examples at all refuses with the instruction`() {
        CRunner.generate(skeleton("int", "int n"), emptyList())
            .shouldBeInstanceOf<Runner.Refused>()
            .reason shouldContain "press Run Code"
    }

    /** The C-specific half of the stdin shape: the measured skeleton is `int main(void)`. */
    @org.junit.jupiter.api.Test
    fun `a main that takes arguments is refused because the judge passes none`() {
        val code = "int main(int argc, char** argv) {\n    return 0;\n}"

        CRunner.generate(code, listOf(ProblemExample("\"1\"", "\"1\"")))
            .shouldBeInstanceOf<Runner.Refused>()
            .reason shouldContain "main takes arguments"
    }

    // Fixtures -----------------------------------------------------------------------------------

    private data class Rendered(val parameter: String, val input: String, val expectedSource: String) {
        fun asTest(): DynamicTest = DynamicTest.dynamicTest("$parameter ← $input → $expectedSource") {
            CRunner.generate(skeleton("int", parameter), listOf(ProblemExample(input, "0")))
                .shouldBeInstanceOf<Runner.Generated>()
                .source shouldContain expectedSource
        }
    }

    private data class Returned(val returnType: String, val expected: String, val want: String, val check: String) {
        fun asTest(): DynamicTest = DynamicTest.dynamicTest("$returnType returning $expected") {
            val source = CRunner.generate(skeleton(returnType, "int n"), listOf(ProblemExample("7", expected)))
                .shouldBeInstanceOf<Runner.Generated>()
                .source
            source shouldContain want
            source shouldContain check
        }
    }

    private data class Refusal(val parameter: String, val input: String, val why: String) {
        fun asTest(): DynamicTest = DynamicTest.dynamicTest("$parameter ← $input ($why)") {
            CRunner.generate(skeleton("int", parameter), listOf(ProblemExample(input, "0")))
                .shouldBeInstanceOf<Runner.Refused>()
        }
    }

    private data class ReturnRefusal(val returnType: String, val expected: String, val why: String) {
        fun asTest(): DynamicTest = DynamicTest.dynamicTest("$returnType ← $expected ($why)") {
            CRunner.generate(skeleton(returnType, "int n"), listOf(ProblemExample("7", expected)))
                .shouldBeInstanceOf<Runner.Refused>()
        }
    }

    private data class Detail(val why: String, val code: String, val example: ProblemExample, val says: String)

    private companion object {
        const val ARRAY_PARAM = "int n[], size_t n_len"
        const val GRID_PARAM = "int** n, size_t n_rows, size_t n_cols"

        fun skeleton(returnType: String, parameters: String): String =
            "$returnType solution($parameters) {\n    return 0;\n}"
    }
}
