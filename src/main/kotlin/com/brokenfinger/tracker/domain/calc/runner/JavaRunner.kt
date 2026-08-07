package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What the generator produced: a file to write, or a refusal that says why there is none. */
sealed interface Runner {
    data class Generated(
        val fileName: String,
        val source: String,
        /** Companion artifacts written beside the runner — C#'s project file (#88). */
        val extras: List<ExtraFile> = emptyList(),
    ) : Runner

    data class ExtraFile(val fileName: String, val source: String)

    data class Refused(val reason: String) : Runner
}

/**
 * `(user's code, measured examples) → RunnerTest.java`, or a refusal.
 *
 * The line that may not be crossed (#37): **a runner that compiles and tests the wrong thing
 * is worse than none.** So every conversion is checked against the user's own signature —
 * the only trustworthy source of types, because the wire sends `"3, 2"` with neither names
 * nor arity — and anything that does not line up refuses with a reason instead of producing
 * a best-effort file. Self-contained on purpose: plain `main`, standard library only, so it
 * runs with `java RunnerTest.java` and needs no project scaffolding in the records repo.
 */
object JavaRunner {
    fun generate(code: String, examples: List<ProblemExample>): Runner {
        if (examples.isEmpty()) {
            return Runner.Refused("no examples were captured — press Run Code (코드 실행) once and they will be")
        }
        return when (ProblemShape.of(code)) {
            ProblemShape.SOLUTION_FUNCTION -> solutionRunner(code, examples)
            ProblemShape.STDIN_MAIN -> stdinRunner(examples)
            ProblemShape.UNRECOGNISED -> Runner.Refused("the solution matches neither measured shape (protocol §7.1)")
        }
    }

    // Solution-style ------------------------------------------------------------------------

    private fun solutionRunner(code: String, examples: List<ProblemExample>): Runner {
        val signature = JavaSignature.of(code)
            ?: return Runner.Refused(
                "the solution signature could not be read — only simple parameter types are supported",
            )
        val calls = examples.mapIndexed { index, example ->
            callFor(index, example, signature) ?: return refusalFor(index, example, signature)
        }
        return Runner.Generated(FILE_NAME, solutionSource(calls))
    }

    private data class Call(val index: Int, val arguments: String, val expected: String)

    private fun callFor(index: Int, example: ProblemExample, signature: JavaSignature): Call? {
        val raw = example.input ?: return null
        val values = ExampleValues.arguments(raw) ?: return null
        if (values.size != signature.parameters.size) return null
        val arguments = values.zip(signature.parameters).map { (value, parameter) ->
            literalOf(value, parameter.type) ?: return null
        }
        val expected = example.expected?.let { ExampleValues.single(it) }?.let { literalOf(it, signature.returnType) }
        return Call(index, arguments.joinToString(", "), expected ?: "null /* expected value not captured */")
    }

    private fun refusalFor(index: Int, example: ProblemExample, signature: JavaSignature): Runner.Refused {
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
     * A JSON value as a Java literal **of the declared type** — the declaration decides, the
     * value only has to fit it. Null when it does not fit; the caller refuses.
     */
    private fun literalOf(value: JsonElement, type: String): String? = when {
        type.endsWith("[]") -> arrayLiteral(value, type)
        else -> scalarLiteral(value, type)
    }

    private fun arrayLiteral(value: JsonElement, type: String): String? {
        val array = value as? JsonArray ?: return null
        val elementType = type.removeSuffix("[]")
        val elements = array.map { literalOf(it, elementType) ?: return null }
        return "new $type{${elements.joinToString(", ")}}"
    }

    private fun scalarLiteral(value: JsonElement, type: String): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return when (type) {
            "int" -> primitive.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toString()
            "long" -> primitive.longOrNull?.let { "${it}L" }
            "double" -> primitive.doubleOrNull?.toString()
            "boolean" -> primitive.booleanOrNull?.toString()
            "String" -> if (primitive.isString) quoted(primitive.content) else null
            else -> null
        }
    }

    private fun quoted(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private fun solutionSource(calls: List<Call>): String {
        val cases = calls.joinToString("\n") { call ->
            """        check(${call.index + 1}, new Solution().solution(${call.arguments}), ${call.expected});"""
        }
        return """
// Generated by programmers-tracker from the judge's own examples — do not edit; it is
// replaced after every run. Run it with:  java RunnerTest.java  (Solution.java beside it)
public class RunnerTest {
    public static void main(String[] args) {
$cases
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static int failures = 0;

    static void check(int index, Object actual, Object expected) {
        boolean ok = java.util.Objects.deepEquals(actual, expected)
            || String.valueOf(stringify(actual)).equals(String.valueOf(stringify(expected)));
        System.out.println("example " + index + (ok ? "  PASS" : "  FAIL — expected "
            + stringify(expected) + ", got " + stringify(actual)));
        if (!ok) failures++;
    }

    static Object stringify(Object value) {
        if (value instanceof int[] a) return java.util.Arrays.toString(a);
        if (value instanceof long[] a) return java.util.Arrays.toString(a);
        if (value instanceof double[] a) return java.util.Arrays.toString(a);
        if (value instanceof boolean[] a) return java.util.Arrays.toString(a);
        if (value instanceof Object[] a) return java.util.Arrays.deepToString(a);
        return value;
    }
}
        """.trimIndent() + "\n"
    }

    // Main-style ----------------------------------------------------------------------------

    /**
     * Main-style needs no type mapping at all: [ProblemExample.input] is the stdin text and
     * the expected value is the stdout, both compared as text. The judge compares
     * trailing-newline-insensitively (a `println`-terminated output passes), so the runner
     * trims trailing newlines on both sides — and nothing else.
     */
    private fun stdinRunner(examples: List<ProblemExample>): Runner {
        val cases = examples.mapIndexed { index, example ->
            val stdin = example.input?.let { ExampleValues.single(it) } as? JsonPrimitive
                ?: return Runner.Refused("example ${index + 1}'s stdin could not be read as a quoted text")
            val expected = example.expected?.let { ExampleValues.single(it) } as? JsonPrimitive
                ?: return Runner.Refused("example ${index + 1}'s expected output could not be read as a quoted text")
            Triple(
                index + 1,
                quoted(stdin.contentOrNull ?: return Runner.Refused("example ${index + 1} is not text")),
                quoted(
                    expected.contentOrNull ?: "",
                ),
            )
        }
        return Runner.Generated(FILE_NAME, stdinSource(cases))
    }

    private fun stdinSource(cases: List<Triple<Int, String, String>>): String {
        val body = cases.joinToString("\n") { (index, stdin, expected) ->
            """        check($index, $stdin, $expected);"""
        }
        return """
// Generated by programmers-tracker from the judge's own examples — do not edit; it is
// replaced after every run. Run it with:  java RunnerTest.java  (Solution.java beside it)
public class RunnerTest {
    public static void main(String[] args) throws Exception {
$body
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static int failures = 0;

    static void check(int index, String stdin, String expected) throws Exception {
        java.io.InputStream originalIn = System.in;
        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setIn(new java.io.ByteArrayInputStream(stdin.getBytes()));
            System.setOut(new java.io.PrintStream(captured, true));
            Solution.main(new String[0]);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        // Two normalisations, and only these two. Trailing newlines: the judge accepts a
        // println-terminated last line. Line separators: the judge's expected output uses \n,
        // but THIS runner runs on the user's machine, where println emits \r\n on Windows —
        // a platform artifact, not an answer difference. (Caught by CI's windows-latest gate.)
        String actual = captured.toString().replace("\r\n", "\n").replaceAll("\n+$", "");
        String want = expected.replace("\r\n", "\n").replaceAll("\n+$", "");
        boolean ok = actual.equals(want);
        System.out.println("example " + index + (ok ? "  PASS" : "  FAIL — expected <" + want + ">, got <" + actual + ">"));
        if (!ok) failures++;
    }
}
        """.trimIndent() + "\n"
    }

    private const val FILE_NAME = "RunnerTest.java"
}
