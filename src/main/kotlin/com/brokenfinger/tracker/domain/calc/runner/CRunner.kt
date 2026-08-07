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
 * `(user's code, measured examples) → runner_test.c`, or a refusal (#86).
 *
 * Sixth language in the measured support order, and the one where **one wire value is not
 * one argument**: a 1-D example value expands to `arg, arg_len`, a 2-D one to
 * `arg, arg_rows, arg_cols`, matching the skeleton convention [CSignature] parses. A 2-D
 * value is staged as row arrays gathered behind a pointer array, because an `int[2][2]`
 * does not convert to the declared `int**`. A returned `int*` is compared over the
 * expected answer's length — the judge's own convention, the length being implied by the
 * answer — and C has no untyped placeholder, so like C++ a missing expected value refuses.
 *
 * Main-style reuses C++'s rename-and-include move, but C has no stream objects to swap, so
 * each example stages stdin in a temp file and `freopen`s both standard streams — which is
 * also why the main-style harness reports its progress on **stderr**: stdout belongs to
 * the solution under test.
 */
object CRunner {
    fun generate(code: String, examples: List<ProblemExample>): Runner {
        if (examples.isEmpty()) {
            return Runner.Refused("no examples were captured — press Run Code (코드 실행) once and they will be")
        }
        return when (ProblemShape.ofC(code)) {
            ProblemShape.SOLUTION_FUNCTION -> solutionRunner(code, examples)
            ProblemShape.STDIN_MAIN -> stdinRunner(code, examples)
            ProblemShape.UNRECOGNISED -> Runner.Refused("the solution matches neither measured shape (protocol §7.1)")
            ProblemShape.AMBIGUOUS -> Runner.Refused(AMBIGUOUS_REASON)
        }
    }

    // Solution-style ------------------------------------------------------------------------

    private fun solutionRunner(code: String, examples: List<ProblemExample>): Runner {
        val signature = CSignature.of(code)
            ?: return Runner.Refused(
                "the solution signature could not be read — supported are the measured " +
                    "skeleton forms: scalars, const char*, int arrays with their _len, " +
                    "and int grids with their _rows/_cols",
            )
        val blocks = examples.mapIndexed { index, example ->
            blockFor(index, example, signature) ?: return refusalFor(index, example, signature)
        }
        return Runner.Generated(FILE_NAME, solutionSource(blocks.joinToString("\n")))
    }

    /** One example as a scoped block: staged locals, the call, the kind-matched check. */
    private fun blockFor(index: Int, example: ProblemExample, signature: CSignature): String? {
        val raw = example.input ?: return null
        val values = ExampleValues.arguments(raw) ?: return null
        if (values.size != signature.parameters.size) return null
        val locals = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        values.zip(signature.parameters).forEachIndexed { position, (value, parameter) ->
            val staged = staged("arg${position + 1}", value, parameter) ?: return null
            locals += staged.first
            arguments += staged.second
        }
        val expected = example.expected?.let { ExampleValues.single(it) } ?: return null
        val check = checkFor(index, "solution(${arguments.joinToString(", ")})", signature.returnType, expected)
            ?: return null
        return buildString {
            appendLine("    {")
            locals.forEach { appendLine(it) }
            check.forEach { appendLine(it) }
            append("    }")
        }
    }

    /** The locals that stage one wire value, and the argument expansion that passes them. */
    private fun staged(base: String, value: JsonElement, parameter: CParam): Pair<List<String>, String>? =
        when (parameter) {
            is CScalarParam -> scalarLiteral(value, parameter.type)?.let {
                listOf("        ${parameter.type} $base = $it;") to base
            }
            is CTextParam -> textLiteral(value)?.let {
                listOf("        const char* $base = $it;") to base
            }
            is CIntArrayParam -> arrayLocals(base, value)
            is CIntGridParam -> gridLocals(base, value)
        }

    private fun arrayLocals(base: String, value: JsonElement): Pair<List<String>, String>? {
        val array = value as? JsonArray ?: return null
        val elements = array.map { intLiteral(it) ?: return null }
        return listOf(
            // C forbids empty braces before C23; a one-slot array with length 0 reads the same.
            "        int $base[] = {${if (elements.isEmpty()) "0" else elements.joinToString(", ")}};",
            "        size_t ${base}_len = ${elements.size};",
        ) to "$base, ${base}_len"
    }

    /** Row arrays behind a pointer array — an `int[2][2]` does not convert to `int**`. */
    private fun gridLocals(base: String, value: JsonElement): Pair<List<String>, String>? {
        val rows = (value as? JsonArray)?.map { it as? JsonArray ?: return null } ?: return null
        if (rows.isEmpty()) return null
        val cols = rows.first().size
        if (cols == 0 || rows.any { it.size != cols }) return null
        val locals = mutableListOf<String>()
        rows.forEachIndexed { r, row ->
            val elements = row.map { intLiteral(it) ?: return null }
            locals += "        int ${base}_r$r[] = {${elements.joinToString(", ")}};"
        }
        locals += "        int* $base[] = {${rows.indices.joinToString(", ") { "${base}_r$it" }}};"
        locals += "        size_t ${base}_rows = ${rows.size};"
        locals += "        size_t ${base}_cols = $cols;"
        return locals to "$base, ${base}_rows, ${base}_cols"
    }

    private fun checkFor(index: Int, call: String, returnType: CReturn, expected: JsonElement): List<String>? =
        when (returnType) {
            is CScalarReturn -> {
                val literal = scalarLiteral(expected, returnType.type) ?: return null
                listOf(
                    "        ${returnType.type} want = $literal;",
                    "        ${CHECKS[returnType.type]}(${index + 1}, $call, want);",
                )
            }
            CTextReturn -> textLiteral(expected)?.let {
                listOf("        runner_check_str(${index + 1}, $call, $it);")
            }
            CIntArrayReturn -> {
                val array = expected as? JsonArray ?: return null
                val elements = array.map { intLiteral(it) ?: return null }
                listOf(
                    "        int want[] = {${if (elements.isEmpty()) "0" else elements.joinToString(", ")}};",
                    "        runner_check_ints(${index + 1}, $call, want, ${elements.size});",
                )
            }
        }

    private fun refusalFor(index: Int, example: ProblemExample, signature: CSignature): Runner.Refused {
        val values = example.input?.let { ExampleValues.arguments(it) }
        val expected = example.expected?.let { ExampleValues.single(it) }
        val detail = when {
            values == null -> "example ${index + 1} could not be parsed as an argument list"
            values.size != signature.parameters.size ->
                "example ${index + 1} has ${values.size} argument(s) but solution declares " +
                    "${signature.parameters.size} (arrays count once — their sizes are not wire values)"
            expected == null -> "example ${index + 1}'s expected value was not captured"
            else -> "example ${index + 1} does not fit the declared parameter types"
        }
        return Runner.Refused("$detail — refusing rather than generating a runner that tests the wrong thing")
    }

    // Literals ------------------------------------------------------------------------------

    private fun intLiteral(value: JsonElement): String? =
        (value as? JsonPrimitive)?.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toString()

    private fun scalarLiteral(value: JsonElement, type: String): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return when (type) {
            "int" -> intLiteral(primitive)
            "long long" -> primitive.longOrNull?.let { "${it}LL" }
            "double" -> primitive.doubleOrNull?.toString()
            "bool" -> primitive.booleanOrNull?.toString()
            else -> null
        }
    }

    private fun textLiteral(value: JsonElement): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.let { quoted(it.content) }

    private fun quoted(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private val CHECKS = mapOf(
        "int" to "runner_check_int",
        "long long" to "runner_check_ll",
        "double" to "runner_check_double",
        "bool" to "runner_check_bool",
    )

    private fun solutionSource(blocks: String): String = """
$HEADER
// The parsed parameter convention itself speaks size_t; its visibility is this file's premise.
#include <stddef.h>

#include "Solution.c"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

static int runner_failures = 0;

static void runner_check_int(int index, int actual, int expected) {
    if (actual == expected) {
        printf("example %d  PASS\n", index);
        return;
    }
    printf("example %d  FAIL — expected %d, got %d\n", index, expected, actual);
    runner_failures++;
}

static void runner_check_ll(int index, long long actual, long long expected) {
    if (actual == expected) {
        printf("example %d  PASS\n", index);
        return;
    }
    printf("example %d  FAIL — expected %lld, got %lld\n", index, expected, actual);
    runner_failures++;
}

static void runner_check_double(int index, double actual, double expected) {
    if (actual == expected) {
        printf("example %d  PASS\n", index);
        return;
    }
    printf("example %d  FAIL — expected %g, got %g\n", index, expected, actual);
    runner_failures++;
}

static void runner_check_bool(int index, bool actual, bool expected) {
    if (actual == expected) {
        printf("example %d  PASS\n", index);
        return;
    }
    printf("example %d  FAIL — expected %s, got %s\n", index, expected ? "true" : "false", actual ? "true" : "false");
    runner_failures++;
}

static void runner_check_str(int index, const char* actual, const char* expected) {
    if (actual != NULL && strcmp(actual, expected) == 0) {
        printf("example %d  PASS\n", index);
        return;
    }
    printf("example %d  FAIL — expected \"%s\", got ", index, expected);
    if (actual == NULL) {
        printf("NULL\n");
    } else {
        printf("\"%s\"\n", actual);
    }
    runner_failures++;
}

static void runner_print_ints(const int* values, size_t length) {
    fputs("[", stdout);
    for (size_t i = 0; i < length; i++) {
        if (i > 0) {
            fputs(", ", stdout);
        }
        printf("%d", values[i]);
    }
    fputs("]", stdout);
}

/* The judge's own convention: the returned array's length is implied by the answer. */
static void runner_check_ints(int index, const int* actual, const int* expected, size_t length) {
    int ok = actual != NULL;
    for (size_t i = 0; ok && i < length; i++) {
        ok = actual[i] == expected[i];
    }
    if (ok) {
        printf("example %d  PASS\n", index);
        return;
    }
    printf("example %d  FAIL — expected ", index);
    runner_print_ints(expected, length);
    fputs(", got ", stdout);
    if (actual == NULL) {
        fputs("NULL", stdout);
    } else {
        runner_print_ints(actual, length);
    }
    printf("\n");
    runner_failures++;
}

int main(void) {
$blocks
    if (runner_failures == 0) {
        printf("ALL PASS\n");
    } else {
        printf("%d FAILED\n", runner_failures);
    }
    return runner_failures == 0 ? 0 : 1;
}
    """.trimIndent() + "\n"

    // Main-style ----------------------------------------------------------------------------

    /** The measured `main` (181951) takes no arguments. */
    private val PLAIN_MAIN = Regex("""\bint\s+main\s*\(\s*(?:void)?\s*\)""")

    private fun stdinRunner(code: String, examples: List<ProblemExample>): Runner {
        if (!PLAIN_MAIN.containsMatchIn(code)) {
            return Runner.Refused(
                "main takes arguments — the measured skeleton is int main(void), and the judge passes none",
            )
        }
        val cases = examples.mapIndexed { index, example ->
            val stdin = (example.input?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s stdin could not be read as a quoted text")
            val expected = (example.expected?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s expected output could not be read as a quoted text")
            "    runner_check(${index + 1}, ${quoted(stdin)}, ${quoted(expected)});"
        }
        return Runner.Generated(FILE_NAME, stdinSource(cases.joinToString("\n")))
    }

    /**
     * Renames the user's `main` via the preprocessor and calls it per example with both
     * standard streams `freopen`ed to temp files — C has no stream objects to swap, and
     * skipping the restore dance keeps the harness portable: progress goes to **stderr**
     * instead, because stdout belongs to the solution under test. `freopen` also resets
     * the stream state the previous example read to EOF. The same two normalisations as
     * every runner, and only those.
     */
    private fun stdinSource(cases: String): String = """
$HEADER_MAIN
#include <stddef.h>

#define main runner_solution_main
#include "Solution.c"
#undef main

#include <stdio.h>
#include <string.h>

static int runner_failures = 0;

static void runner_normalize(char* text) {
    char* write = text;
    for (char* read = text; *read != '\0'; read++) {
        if (read[0] == '\r' && read[1] == '\n') {
            continue;
        }
        *write++ = *read;
    }
    *write = '\0';
    size_t length = strlen(text);
    while (length > 0 && text[length - 1] == '\n') {
        text[--length] = '\0';
    }
}

static void runner_check(int index, const char* stdin_text, const char* expected) {
    FILE* staged = fopen("runner_stdin.tmp", "wb");
    if (staged == NULL) {
        fprintf(stderr, "example %d  FAIL — could not stage stdin\n", index);
        runner_failures++;
        return;
    }
    fputs(stdin_text, staged);
    fclose(staged);
    fflush(stdout);
    if (freopen("runner_stdin.tmp", "rb", stdin) == NULL) {
        fprintf(stderr, "example %d  FAIL — could not redirect stdin\n", index);
        runner_failures++;
        return;
    }
    if (freopen("runner_stdout.tmp", "wb", stdout) == NULL) {
        fprintf(stderr, "example %d  FAIL — could not redirect stdout\n", index);
        runner_failures++;
        return;
    }
    runner_solution_main();
    fflush(stdout);
    static char actual[65536];
    FILE* captured = fopen("runner_stdout.tmp", "rb");
    size_t read = captured == NULL ? 0 : fread(actual, 1, sizeof(actual) - 1, captured);
    if (captured != NULL) {
        fclose(captured);
    }
    actual[read] = '\0';
    static char want[65536];
    strncpy(want, expected, sizeof(want) - 1);
    want[sizeof(want) - 1] = '\0';
    runner_normalize(actual);
    runner_normalize(want);
    if (strcmp(actual, want) == 0) {
        fprintf(stderr, "example %d  PASS\n", index);
        return;
    }
    fprintf(stderr, "example %d  FAIL — expected <%s>, got <%s>\n", index, want, actual);
    runner_failures++;
}

int main(void) {
$cases
    if (runner_failures == 0) {
        fprintf(stderr, "ALL PASS\n");
    } else {
        fprintf(stderr, "%d FAILED\n", runner_failures);
    }
    remove("runner_stdin.tmp");
    remove("runner_stdout.tmp");
    return runner_failures == 0 ? 0 : 1;
}
    """.trimIndent() + "\n"

    private const val FILE_NAME = "runner_test.c"

    private const val HEADER =
        "// Generated by programmers-tracker from the judge's own examples — do not edit; it is\n" +
            "// replaced after every run. Compile and run with (Solution.c beside it):\n" +
            "//   gcc runner_test.c -o runner_test && ./runner_test"

    private const val HEADER_MAIN =
        "// Generated by programmers-tracker from the judge's own examples — do not edit; it is\n" +
            "// replaced after every run. Compile and run with (Solution.c beside it):\n" +
            "//   gcc runner_test.c -o runner_test && ./runner_test\n" +
            "// Progress prints to stderr — stdout belongs to the solution under test."
}
