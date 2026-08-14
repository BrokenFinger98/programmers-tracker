package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The other half of every generator: the **stdin** problems, where the solution is a `main` that
 * reads input and prints output rather than a `solution` function to call (#272).
 *
 * `stdinRunner` was the single largest uncovered shape left after the value tables — 64 branches
 * across seven languages, all of them the same three questions:
 *
 * - the input and the expected output are read as **text**, which is what a stdin problem is fed;
 * - a value that has no text — an array, an object, a missing half — is **refused with a reason**,
 *   never defaulted. #98 is the precedent and it is named in the code: an empty string here once
 *   made the harness assert that the program prints nothing, three lines from the guard that does
 *   it right.
 * - the text is escaped into the source, because a raw newline is what a stdin example is *made*
 *   of and an unescaped one ends the string literal.
 *
 * A language missing from [LANGUAGES] shows up as a gap in a list, the same shape the compile-error
 * fixtures use (development-rules §6.2).
 */
class StdinRunnerTableTest {
    @TestFactory
    fun `every generator builds a runner from quoted stdin and expected output`() = LANGUAGES.map { language ->
        DynamicTest.dynamicTest(language.name) {
            val runner = language.generate(
                listOf(ProblemExample("\"3 4\"", "\"7\"")),
            ).shouldBeInstanceOf<Runner.Generated>()

            runner.source shouldContain "3 4"
            runner.source shouldContain "7"
        }
    }

    /**
     * An unquoted number reads as its own text. The refusal message beside this code says "quoted
     * text", and the check admits any JSON primitive — so a number passes and is fed as `7`.
     *
     * Never measured: no captured stdin example has been unquoted. Pinned as the behaviour that
     * exists rather than as one anybody chose.
     */
    @TestFactory
    fun `an unquoted number is fed as its own text`() = LANGUAGES.map { language ->
        DynamicTest.dynamicTest(language.name) {
            val runner = language.generate(listOf(ProblemExample("7", "34")))
                .shouldBeInstanceOf<Runner.Generated>()

            runner.source shouldContain "34"
        }
    }

    /**
     * A newline is what a multi-line stdin example is made of, and it cannot reach a source file
     * raw. Each generator escapes it its own way; all that is asserted here is that the literal
     * newline is gone.
     */
    @TestFactory
    fun `a multi-line stdin example is escaped rather than embedded raw`() = LANGUAGES.map { language ->
        DynamicTest.dynamicTest(language.name) {
            val runner = language.generate(
                listOf(ProblemExample("\"3\\n4\"", "\"7\"")),
            ).shouldBeInstanceOf<Runner.Generated>()

            runner.source shouldContain "3\\n4"
        }
    }

    /**
     * Refused, never defaulted. Each of these is an example half the judge gave us, and a runner
     * built on the missing half tests something nobody ran.
     */
    @TestFactory
    fun `an example that is not text on both sides is refused`() = LANGUAGES.flatMap { language ->
        listOf(
            // A number is **accepted**, not refused: an example's value is a JSON primitive
            // either way and its text is what a stdin problem is fed. Never measured — no
            // captured stdin example has ever been unquoted — so it is pinned in the accepted
            // table below rather than asserted here on a guess.
            "an array for stdin" to ProblemExample("[1, 2]", "\"7\""),
            "an object for the expected output" to ProblemExample("\"3 4\"", "{\"a\": 1}"),
            "no stdin at all" to ProblemExample(null, "\"7\""),
            "no expected output at all" to ProblemExample("\"3 4\"", null),
        ).map { (why, example) ->
            DynamicTest.dynamicTest("${language.name}: $why") {
                language.generate(listOf(example)).shouldBeInstanceOf<Runner.Refused>()
            }
        }
    }

    private class Language(
        val name: String,
        private val source: String,
        private val runner: (String, List<ProblemExample>) -> Runner,
    ) {
        fun generate(examples: List<ProblemExample>): Runner = runner(source, examples)
    }

    private companion object {
        /** A `main` that reads and prints — the shape that makes each generator take the stdin path. */
        val LANGUAGES = listOf(
            Language(
                "java",
                "public class Solution {\n    public static void main(String[] args) {\n    }\n}",
                JavaRunner::generate,
            ),
            Language(
                "kotlin",
                "fun main() {\n    println(readLine())\n}",
                KotlinRunner::generate,
            ),
            Language(
                "python3",
                "print(input())",
                { code, examples -> PythonRunner.generate(code, examples) },
            ),
            Language(
                "javascript",
                // The measured pattern (ProblemShape.JS_STDIN) — `readFileSync(0)` is a shape
                // nobody has captured, so it reads as UNRECOGNISED rather than as stdin.
                "const readline = require('readline');\nprocess.stdin.on('data', () => {});",
                { code, examples -> JavascriptRunner.generate(code, examples) },
            ),
            Language(
                "cpp",
                "int main() {\n    return 0;\n}",
                CppRunner::generate,
            ),
            Language(
                "c",
                "int main(void) {\n    return 0;\n}",
                CRunner::generate,
            ),
            Language(
                "csharp",
                "public class Solution {\n    public static void Main(string[] args) {\n    }\n}",
                CsharpRunner::generate,
            ),
        )
    }
}
