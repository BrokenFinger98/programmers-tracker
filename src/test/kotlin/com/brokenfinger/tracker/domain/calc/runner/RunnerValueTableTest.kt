package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * One value table for **every** generator, because the shapes are the same question asked seven
 * ways (#272).
 *
 * The execution suites prove a generated runner compiles and passes; they say nothing about the
 * shapes nobody wrote a case for, which is why `domain/calc/runner` sat at 66% with all seven of
 * them green. The literal renderers are where those branches live — one arm per type each
 * generator knows, and one refusal each of them owes when the example does not match.
 *
 * **The refusals are the half that matters.** A literal rendered wrong produces a file that either
 * fails to compile — noise — or compiles and tests something other than the problem, which is the
 * silent-wrong-data failure the constitution ranks worst.
 *
 * **A language missing from [LANGUAGES] shows up as a gap in a list**, which is the shape
 * development-rules §6.2 already uses for the compile-error fixtures: #212 happened because six of
 * seven languages were classified by patterns written for two others, and nothing recorded which.
 */
class RunnerValueTableTest {
    @TestFactory
    fun `every generator renders the literal its language needs`() = LANGUAGES.flatMap { language ->
        language.renders.map { (declared, rendered) ->
            DynamicTest.dynamicTest("${language.name}: $declared → $rendered") {
                val runner = language.generate(declared.type, declared.input)
                    .shouldBeInstanceOf<Runner.Generated>()

                runner.source shouldContain rendered
            }
        }
    }

    /** Nothing is rendered best-effort: a value the declared type cannot hold refuses the file. */
    @TestFactory
    fun `every generator refuses a value its declared type cannot hold`() = LANGUAGES.flatMap { language ->
        language.refuses.map { declared ->
            DynamicTest.dynamicTest("${language.name}: $declared refused") {
                language.generate(declared.type, declared.input).shouldBeInstanceOf<Runner.Refused>()
            }
        }
    }

    private data class Value(val type: String, val input: String) {
        override fun toString(): String = "$type ← $input"
    }

    private class Language(
        val name: String,
        private val source: (String) -> String,
        private val runner: (String, List<ProblemExample>) -> Runner,
        val renders: List<Pair<Value, String>>,
        val refuses: List<Value>,
    ) {
        fun generate(type: String, input: String): Runner =
            runner(source(type), listOf(ProblemExample(input, EXPECTED)))
    }

    private companion object {
        /** The generators' own return type is fixed by [source]; only the parameter varies. */
        const val EXPECTED = "0"

        val LANGUAGES = listOf(
            Language(
                name = "kotlin",
                source = { "class Solution {\n    fun solution(value: $it): Int {\n        return 0\n    }\n}" },
                runner = KotlinRunner::generate,
                renders = listOf(
                    Value("Int", "7") to "solution(7)",
                    Value("Long", "7") to "solution(7L)",
                    Value("Double", "1.5") to "solution(1.5)",
                    Value("Boolean", "true") to "solution(true)",
                    Value("String", "\"ab\"") to """solution("ab")""",
                    Value("IntArray", "[1, 2]") to "intArrayOf(1, 2)",
                    Value("LongArray", "[1, 2]") to "longArrayOf(1L, 2L)",
                    Value("DoubleArray", "[1.5]") to "doubleArrayOf(1.5)",
                    Value("BooleanArray", "[true]") to "booleanArrayOf(true)",
                ),
                refuses = listOf(
                    // 32 bits, and Programmers' examples are not bounded by them. Coercing would
                    // test a different number than the judge did.
                    Value("Int", "3000000000"),
                    Value("String", "7"),
                    Value("IntArray", "7"),
                    Value("IntArray", "[\"a\"]"),
                ),
            ),
            Language(
                name = "python3",
                // Python declares no types, so the value itself is the whole contract.
                source = { "def solution(value):\n    return 0" },
                runner = { code, examples -> PythonRunner.generate(code, examples) },
                renders = listOf(
                    Value("", "7") to "solution(7)",
                    Value("", "1.5") to "solution(1.5)",
                    Value("", "true") to "solution(True)",
                    Value("", "false") to "solution(False)",
                    Value("", "null") to "solution(None)",
                    Value("", "\"ab\"") to """solution("ab")""",
                    Value("", "[1, 2]") to "solution([1, 2])",
                    Value("", "[[1], [2]]") to "solution([[1], [2]])",
                ),
                refuses = emptyList(),
            ),
            Language(
                name = "javascript",
                source = { "function solution(value) {\n    return 0;\n}" },
                runner = { code, examples -> JavascriptRunner.generate(code, examples) },
                renders = listOf(
                    Value("", "7") to "solution(7)",
                    Value("", "true") to "solution(true)",
                    Value("", "null") to "solution(null)",
                    Value("", "\"ab\"") to """solution("ab")""",
                    // kotlinx serialises a JsonArray without spaces, and JSON is JavaScript —
                    // so the literal embeds verbatim rather than being rebuilt.
                    Value("", "[1, 2]") to "solution([1,2])",
                ),
                refuses = emptyList(),
            ),
            Language(
                name = "cpp",
                source = { "int solution($it value) {\n    return 0;\n}" },
                runner = CppRunner::generate,
                // C++ names every argument before calling, so the literal shows up in the local
                // rather than inside the call: `int arg1 = 7;` then `solution(arg1)`.
                renders = listOf(
                    Value("int", "7") to "int arg1 = 7;",
                    Value("long long", "7") to "long long arg1 = 7LL;",
                    Value("double", "1.5") to "double arg1 = 1.5;",
                    Value("bool", "true") to "bool arg1 = true;",
                    Value("string", "\"ab\"") to """string arg1 = "ab";""",
                    Value("vector<int>", "[1, 2]") to "vector<int>{1, 2}",
                    Value("vector<string>", "[\"a\"]") to """vector<string>{"a"}""",
                ),
                refuses = listOf(
                    Value("int", "3000000000"),
                    Value("string", "7"),
                    Value("vector<int>", "7"),
                    Value("vector<int>", "[\"a\"]"),
                ),
            ),
            Language(
                name = "csharp",
                source = {
                    "public class Solution {\n    public int solution($it value) {\n        return 0;\n    }\n}"
                },
                runner = CsharpRunner::generate,
                renders = listOf(
                    Value("int", "7") to "solution(7)",
                    Value("long", "7") to "solution(7L)",
                    Value("double", "1.5") to "solution(1.5)",
                    Value("bool", "true") to "solution(true)",
                    Value("string", "\"ab\"") to """solution("ab")""",
                    Value("int[]", "[1, 2]") to "new int[] {1, 2}",
                    Value("string[]", "[\"a\"]") to """new string[] {"a"}""",
                ),
                refuses = listOf(
                    Value("int", "3000000000"),
                    Value("string", "7"),
                    Value("int[]", "7"),
                    Value("int[]", "[\"a\"]"),
                ),
            ),
        )
    }
}
