package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `(user's code, measured examples) → runner_test.kt`, or a refusal (#84).
 *
 * Fifth language in the measured support order. Both shapes compile in **one unit** with
 * the user's file — `kotlinc Solution.kt runner_test.kt` — and Kotlin's two traps are both
 * about `main`:
 *
 * - the harness entry is an `object RunnerTest { @JvmStatic fun main }`; a top-level
 *   `fun main` would collide with the user's own (181951's skeleton is exactly that);
 * - the user's `main` is reached through a **top-level bridge function**, because inside
 *   the object an unqualified `main(...)` resolves to the harness's own entry, and the
 *   `SolutionKt` facade class Java callers would use is invisible to Kotlin resolution.
 *
 * Equality is by content, not reference: Kotlin arrays compare with `==` as references, so
 * the harness dispatches to `contentEquals`/`contentDeepEquals` — the reason the array
 * cases in [KotlinRunnerExecutionTest] run for real.
 */
object KotlinRunner {
    fun generate(code: String, examples: List<ProblemExample>): Runner {
        if (examples.isEmpty()) {
            return Runner.Refused("no examples were captured — press Run Code (코드 실행) once and they will be")
        }
        return when (ProblemShape.ofKotlin(code)) {
            ProblemShape.SOLUTION_FUNCTION -> solutionRunner(code, examples)
            ProblemShape.STDIN_MAIN -> stdinRunner(code, examples)
            ProblemShape.UNRECOGNISED -> Runner.Refused("the solution matches neither measured shape (protocol §7.1)")
        }
    }

    // Solution-style ------------------------------------------------------------------------

    private fun solutionRunner(code: String, examples: List<ProblemExample>): Runner {
        val signature = KotlinSignature.of(code)
            ?: return Runner.Refused(
                "the solution signature could not be read — supported types are Int, Long, " +
                    "Double, Boolean, String, their primitive arrays and Array of them, " +
                    "and the return type must be declared",
            )
        val calls = examples.mapIndexed { index, example ->
            callFor(index, example, signature) ?: return refusalFor(index, example, signature)
        }
        return Runner.Generated(FILE_NAME, solutionSource(calls.joinToString("\n")))
    }

    private fun callFor(index: Int, example: ProblemExample, signature: KotlinSignature): String? {
        val raw = example.input ?: return null
        val values = ExampleValues.arguments(raw) ?: return null
        if (values.size != signature.parameters.size) return null
        val arguments = values.zip(signature.parameters).map { (value, parameter) ->
            literalOf(value, parameter.type) ?: return null
        }
        val expected = example.expected?.let { ExampleValues.single(it) }
            ?.let { literalOf(it, signature.returnType) }
        return "        check(${index + 1}, Solution().solution(${arguments.joinToString(", ")}), " +
            "${expected ?: "null /* expected value not captured */"})"
    }

    private fun refusalFor(index: Int, example: ProblemExample, signature: KotlinSignature): Runner.Refused {
        val values = example.input?.let { ExampleValues.arguments(it) }
        val detail = when {
            values == null -> "example ${index + 1} could not be parsed as an argument list"
            values.size != signature.parameters.size ->
                "example ${index + 1} has ${values.size} argument(s) but solution declares ${signature.parameters.size}"
            else -> "example ${index + 1} does not fit the declared parameter types"
        }
        return Runner.Refused("$detail — refusing rather than generating a runner that tests the wrong thing")
    }

    /**
     * A JSON value as a Kotlin literal **of the declared type** — the declaration decides,
     * the value only has to fit it. Null when it does not fit; the caller refuses.
     */
    private fun literalOf(value: JsonElement, type: String): String? = when (type) {
        "Int" -> scalar(value) { it.longOrNull?.takeIf { v -> v in Int.MIN_VALUE..Int.MAX_VALUE }?.toString() }
        "Long" -> scalar(value) { it.longOrNull?.let { v -> "${v}L" } }
        "Double" -> scalar(value) { it.doubleOrNull?.toString() }
        "Boolean" -> scalar(value) { it.booleanOrNull?.toString() }
        "String" -> scalar(value) { p -> p.takeIf { it.isString }?.let { quoted(it.content) } }
        "IntArray" -> arrayLiteral(value, "intArrayOf", "Int")
        "LongArray" -> arrayLiteral(value, "longArrayOf", "Long")
        "DoubleArray" -> arrayLiteral(value, "doubleArrayOf", "Double")
        "BooleanArray" -> arrayLiteral(value, "booleanArrayOf", "Boolean")
        else -> objectArray(value, type)
    }

    private fun scalar(value: JsonElement, convert: (JsonPrimitive) -> String?): String? =
        (value as? JsonPrimitive)?.let(convert)

    private fun arrayLiteral(value: JsonElement, factory: String, elementType: String): String? {
        val array = value as? JsonArray ?: return null
        val elements = array.map { literalOf(it, elementType) ?: return null }
        return "$factory(${elements.joinToString(", ")})"
    }

    /** `arrayOf<T>(...)` with the type argument always explicit — an empty array needs it. */
    private fun objectArray(value: JsonElement, type: String): String? {
        if (!type.startsWith("Array<") || !type.endsWith(">")) return null
        val array = value as? JsonArray ?: return null
        val elementType = type.removeSurrounding("Array<", ">")
        val elements = array.map { literalOf(it, elementType) ?: return null }
        return "arrayOf<$elementType>(${elements.joinToString(", ")})"
    }

    /** Kotlin string literals interpolate `$`, so it is escaped alongside the usual set. */
    private fun quoted(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private fun solutionSource(cases: String): String = """
$HEADER

object RunnerTest {
    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
$cases
$FOOTER
    }

    private fun check(index: Int, actual: Any?, expected: Any?) {
        val ok = same(actual, expected)
        val detail = "  FAIL — expected " + stringify(expected) + ", got " + stringify(actual)
        println("example " + index + (if (ok) "  PASS" else detail))
        if (!ok) failures += 1
    }

    // Kotlin arrays compare by reference with ==; the judge compares by content.
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is IntArray && b is IntArray -> a.contentEquals(b)
        a is LongArray && b is LongArray -> a.contentEquals(b)
        a is DoubleArray && b is DoubleArray -> a.contentEquals(b)
        a is BooleanArray && b is BooleanArray -> a.contentEquals(b)
        a is Array<*> && b is Array<*> -> a.contentDeepEquals(b)
        else -> a == b
    }

    private fun stringify(value: Any?): String = when (value) {
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is DoubleArray -> value.contentToString()
        is BooleanArray -> value.contentToString()
        is Array<*> -> value.contentDeepToString()
        else -> value.toString()
    }
}
    """.trimIndent() + "\n"

    // Main-style ----------------------------------------------------------------------------

    /** The measured main (181951) takes `args: Array<String>`; a bare `fun main()` also runs. */
    private val MAIN_WITH_ARGS = Regex("""fun\s+main\s*\(\s*\w+\s*:\s*Array<String>\s*\)""")
    private val MAIN_NO_ARGS = Regex("""fun\s+main\s*\(\s*\)""")

    private fun stdinRunner(code: String, examples: List<ProblemExample>): Runner {
        val bridge = when {
            MAIN_WITH_ARGS.containsMatchIn(code) ->
                "internal fun runnerInvokeSolutionMain() = main(emptyArray())"
            MAIN_NO_ARGS.containsMatchIn(code) ->
                "internal fun runnerInvokeSolutionMain() = main()"
            else -> return Runner.Refused(
                "main's signature is unmeasured — the skeleton declares " +
                    "fun main(args: Array<String>) (editor capture 2026-08-07)",
            )
        }
        val cases = examples.mapIndexed { index, example ->
            val stdin = (example.input?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s stdin could not be read as a quoted text")
            val expected = (example.expected?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s expected output could not be read as a quoted text")
            "        check(${index + 1}, ${quoted(stdin)}, ${quoted(expected)})"
        }
        return Runner.Generated(FILE_NAME, stdinSource(bridge, cases.joinToString("\n")))
    }

    /**
     * Calls the user's `main` per example with the standard streams swapped in-process —
     * Java's `System.setIn` pattern, reached through the top-level bridge. The same two
     * normalisations as every runner, and only those.
     */
    private fun stdinSource(bridge: String, cases: String): String = """
$HEADER

// The user's top-level main is reachable only from package scope — inside the object an
// unqualified main(...) call would resolve to the harness's own entry.
$bridge

object RunnerTest {
    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
$cases
$FOOTER
    }

    private fun check(index: Int, stdinText: String, expected: String) {
        val originalIn = System.`in`
        val originalOut = System.out
        val captured = java.io.ByteArrayOutputStream()
        try {
            System.setIn(java.io.ByteArrayInputStream(stdinText.toByteArray()))
            System.setOut(java.io.PrintStream(captured, true))
            runnerInvokeSolutionMain()
        } finally {
            System.setOut(originalOut)
            System.setIn(originalIn)
        }
        // Two normalisations, and only these two: trailing newlines and CRLF to LF.
        val actual = captured.toString().replace("\r\n", "\n").trimEnd('\n')
        val want = expected.replace("\r\n", "\n").trimEnd('\n')
        val ok = actual == want
        println("example " + index + (if (ok) "  PASS" else "  FAIL — expected <" + want + ">, got <" + actual + ">"))
        if (!ok) failures += 1
    }
}
    """.trimIndent() + "\n"

    private const val FILE_NAME = "runner_test.kt"

    private const val HEADER =
        "// Generated by programmers-tracker from the judge's own examples — do not edit; it is\n" +
            "// replaced after every run. Compile and run with (Solution.kt beside it):\n" +
            "//   kotlinc Solution.kt runner_test.kt -include-runtime -d runner_test.jar " +
            "&& java -cp runner_test.jar RunnerTest"

    private const val FOOTER =
        "        println(if (failures == 0) \"ALL PASS\" else failures.toString() + \" FAILED\")\n" +
            "        if (failures > 0) kotlin.system.exitProcess(1)"
}
