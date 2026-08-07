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
 * `(user's code, measured examples) → runner_test.cpp`, or a refusal (#80).
 *
 * Third language in the measured support order, and the first whose harness shares one
 * translation unit with the user's code: the runner `#include`s `Solution.cpp` directly,
 * so its file-scope identifiers wear a `runner_` prefix. A name the user happens to reuse
 * still fails **loudly at compile time** — never silently testing the wrong thing, which is
 * the one line this series may not cross.
 *
 * Two shape-specific moves, both forced by C++ itself:
 * - solution-style arguments are **named locals**, not inline temporaries, so hand-edited
 *   reference signatures (`vector<int>&`) bind;
 * - main-style renames the user's `main` via the preprocessor and calls it per example
 *   with `cin`/`cout` buffers swapped in-process — measured `int main(void)` only, and a
 *   `main` taking arguments refuses as unmeasured.
 */
object CppRunner {
    fun generate(code: String, examples: List<ProblemExample>): Runner {
        if (examples.isEmpty()) {
            return Runner.Refused("no examples were captured — press Run Code (코드 실행) once and they will be")
        }
        return when (ProblemShape.ofCpp(code)) {
            ProblemShape.SOLUTION_FUNCTION -> solutionRunner(code, examples)
            ProblemShape.STDIN_MAIN -> stdinRunner(code, examples)
            ProblemShape.UNRECOGNISED -> Runner.Refused("the solution matches neither measured shape (protocol §7.1)")
        }
    }

    // Solution-style ------------------------------------------------------------------------

    private fun solutionRunner(code: String, examples: List<ProblemExample>): Runner {
        val signature = CppSignature.of(code)
            ?: return Runner.Refused(
                "the solution signature could not be read — supported types are " +
                    "int, long long, double, bool, string and vector of them",
            )
        val blocks = examples.mapIndexed { index, example ->
            blockFor(index, example, signature) ?: return refusalFor(index, example, signature)
        }
        return Runner.Generated(FILE_NAME, solutionSource(blocks))
    }

    /** One example as a scoped block of typed locals plus its check, or null if it does not fit. */
    private fun blockFor(index: Int, example: ProblemExample, signature: CppSignature): String? {
        val raw = example.input ?: return null
        val values = ExampleValues.arguments(raw) ?: return null
        if (values.size != signature.parameters.size) return null
        val locals = values.zip(signature.parameters).mapIndexed { position, (value, parameter) ->
            val literal = literalOf(value, parameter.type) ?: return null
            "        ${parameter.type} arg${position + 1} = $literal;"
        }
        val expected = example.expected?.let { ExampleValues.single(it) }
            ?.let { literalOf(it, signature.returnType) } ?: return null
        val arguments = (1..values.size).joinToString(", ") { "arg$it" }
        return buildString {
            appendLine("    {")
            locals.forEach { appendLine(it) }
            appendLine("        ${signature.returnType} want = $expected;")
            appendLine("        runner_check(${index + 1}, solution($arguments), want);")
            append("    }")
        }
    }

    private fun refusalFor(index: Int, example: ProblemExample, signature: CppSignature): Runner.Refused {
        val values = example.input?.let { ExampleValues.arguments(it) }
        val expected = example.expected?.let { ExampleValues.single(it) }
            ?.let { literalOf(it, signature.returnType) }
        val detail = when {
            values == null -> "example ${index + 1} could not be parsed as an argument list"
            values.size != signature.parameters.size ->
                "example ${index + 1} has ${values.size} argument(s) but solution declares ${signature.parameters.size}"
            expected == null ->
                "example ${index + 1}'s expected value could not be built as ${signature.returnType}"
            else -> "example ${index + 1} does not fit the declared parameter types"
        }
        return Runner.Refused("$detail — refusing rather than generating a runner that tests the wrong thing")
    }

    /**
     * A JSON value as a C++ literal **of the declared type** — the declaration decides, the
     * value only has to fit it. Null when it does not fit; the caller refuses.
     */
    private fun literalOf(value: JsonElement, type: String): String? = when {
        type.startsWith("vector<") -> vectorLiteral(value, type)
        else -> scalarLiteral(value, type)
    }

    private fun vectorLiteral(value: JsonElement, type: String): String? {
        val array = value as? JsonArray ?: return null
        val elementType = type.removeSurrounding("vector<", ">")
        val elements = array.map { literalOf(it, elementType) ?: return null }
        return "$type{${elements.joinToString(", ")}}"
    }

    private fun scalarLiteral(value: JsonElement, type: String): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return when (type) {
            "int" -> primitive.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toString()
            "long long" -> primitive.longOrNull?.let { "${it}LL" }
            "double" -> primitive.doubleOrNull?.toString()
            "bool" -> primitive.booleanOrNull?.toString()
            "string" -> if (primitive.isString) quoted(primitive.content) else null
            else -> null
        }
    }

    private fun quoted(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private fun solutionSource(blocks: List<String>): String = """
$HEADER
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "Solution.cpp"

static int runner_failures = 0;

template <typename T>
static std::string runner_str(const T& value) {
    std::ostringstream out;
    out << value;
    return out.str();
}

static std::string runner_str(bool value) { return value ? "true" : "false"; }

static std::string runner_str(const std::string& value) { return "\"" + value + "\""; }

template <typename T>
static std::string runner_str(const std::vector<T>& value) {
    std::string out = "[";
    for (size_t i = 0; i < value.size(); ++i) {
        if (i > 0) out += ", ";
        out += runner_str(value[i]);
    }
    return out + "]";
}

template <typename T>
static void runner_check(int index, const T& actual, const T& expected) {
    bool ok = actual == expected;
    std::cout << "example " << index
              << (ok ? std::string("  PASS")
                     : "  FAIL — expected " + runner_str(expected) + ", got " + runner_str(actual))
              << "\n";
    if (!ok) runner_failures++;
}

int main() {
${blocks.joinToString("\n")}
$FOOTER
}
    """.trimIndent() + "\n"

    // Main-style ----------------------------------------------------------------------------

    /** The measured `main` (181951, editor capture 2026-08-07) takes no arguments. */
    private val PLAIN_MAIN = Regex("""\bint\s+main\s*\(\s*(?:void)?\s*\)""")

    private fun stdinRunner(code: String, examples: List<ProblemExample>): Runner {
        if (!PLAIN_MAIN.containsMatchIn(code)) {
            return Runner.Refused(
                "main takes arguments — the measured skeleton is int main(void), " +
                    "and the judge passes none",
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
     * Renames the user's `main` via the preprocessor and calls it per example with the
     * standard streams swapped to in-memory buffers — all in one process, like Java's
     * `System.setIn` swap. `cin` must be cleared between examples: the previous run read its
     * stream to the end and left eof/fail bits set. The same two normalisations as the other
     * runners, and only those: trailing newlines (the judge accepts a terminal newline) and
     * `\r\n` → `\n` (the expected side may carry wire line endings; the swapped string
     * buffers never receive the platform's text-mode translation, but symmetry is free).
     */
    private fun stdinSource(cases: String): String = """
$HEADER
#include <iostream>
#include <sstream>
#include <string>

#define main runner_solution_main
#include "Solution.cpp"
#undef main

static int runner_failures = 0;

static std::string runner_normalized(const std::string& text) {
    std::string out;
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] == '\r' && i + 1 < text.size() && text[i + 1] == '\n') continue;
        out += text[i];
    }
    while (!out.empty() && out.back() == '\n') out.pop_back();
    return out;
}

static void runner_check(int index, const std::string& stdin_text, const std::string& expected) {
    std::istringstream in(stdin_text);
    std::ostringstream captured;
    std::streambuf* old_in = std::cin.rdbuf(in.rdbuf());
    std::streambuf* old_out = std::cout.rdbuf(captured.rdbuf());
    runner_solution_main();
    std::cout.rdbuf(old_out);
    std::cin.rdbuf(old_in);
    std::cin.clear();
    std::string actual = runner_normalized(captured.str());
    std::string want = runner_normalized(expected);
    bool ok = actual == want;
    std::cout << "example " << index
              << (ok ? std::string("  PASS") : "  FAIL — expected <" + want + ">, got <" + actual + ">")
              << "\n";
    if (!ok) runner_failures++;
}

int main() {
$cases
$FOOTER
}
    """.trimIndent() + "\n"

    private const val FILE_NAME = "runner_test.cpp"

    private const val HEADER =
        "// Generated by programmers-tracker from the judge's own examples — do not edit; it is\n" +
            "// replaced after every run. Compile and run with (Solution.cpp beside it):\n" +
            "//   g++ -std=c++17 runner_test.cpp -o runner_test && ./runner_test"

    private const val FOOTER =
        "    std::cout << (runner_failures == 0 ? std::string(\"ALL PASS\")\n" +
            "                                        : std::to_string(runner_failures) + \" FAILED\") << \"\\n\";\n" +
            "    return runner_failures == 0 ? 0 : 1;"
}
