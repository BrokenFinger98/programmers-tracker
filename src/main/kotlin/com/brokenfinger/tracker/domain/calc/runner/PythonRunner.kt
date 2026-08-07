package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `(user's code, measured examples) → runner_test.py`, or a refusal (#78).
 *
 * The second language in the measured support order, and the first where the runner is not
 * the build's own language — which is why [PythonRunnerExecutionTest] running real `python3`
 * children is what earns it "supported", not this file.
 *
 * Python declares no parameter types, so this generator has **one check fewer than Java's**:
 * arity still refuses a mismatched example, types cannot — a JSON `3` and a `"3"` both slot
 * into an untyped parameter. The line is unchanged (a runner that runs and tests the wrong
 * thing is worse than none); the type half of it is simply the judge's own behaviour here,
 * because Python problems are graded on `==` over whatever `solution` returned.
 */
object PythonRunner {
    fun generate(code: String, examples: List<ProblemExample>): Runner {
        if (examples.isEmpty()) {
            return Runner.Refused("no examples were captured — press Run Code (코드 실행) once and they will be")
        }
        return when (ProblemShape.ofPython(code)) {
            ProblemShape.SOLUTION_FUNCTION -> solutionRunner(code, examples)
            ProblemShape.STDIN_MAIN -> stdinRunner(examples)
            ProblemShape.UNRECOGNISED -> Runner.Refused("the solution matches neither measured shape (protocol §7.1)")
            // Unreachable today: this language resolves the both-signals case in favour of
            // the solution declaration rather than calling it ambiguous (see ProblemShape).
            ProblemShape.AMBIGUOUS -> Runner.Refused(AMBIGUOUS_REASON)
        }
    }

    // Solution-style ------------------------------------------------------------------------

    private fun solutionRunner(code: String, examples: List<ProblemExample>): Runner {
        val signature = PythonSignature.of(code)
            ?: return Runner.Refused(
                "the solution signature could not be read — varargs and defaults are not supported",
            )
        val calls = examples.mapIndexed { index, example ->
            callFor(index, example, signature) ?: return refusalFor(index, example, signature)
        }
        return Runner.Generated(FILE_NAME, solutionSource(calls))
    }

    private data class Call(val arguments: String, val expected: String)

    private fun callFor(index: Int, example: ProblemExample, signature: PythonSignature): Call? {
        val raw = example.input ?: return null
        val values = ExampleValues.arguments(raw) ?: return null
        if (values.size != signature.parameters.size) return null
        val arguments = values.map { literalOf(it) ?: return null }
        // No placeholder (#98) — see JavaRunner.callFor.
        val expected = example.expected?.let { ExampleValues.single(it) }?.let { literalOf(it) } ?: return null
        return Call(arguments.joinToString(", "), expected)
    }

    private fun refusalFor(index: Int, example: ProblemExample, signature: PythonSignature): Runner.Refused {
        val values = example.input?.let { ExampleValues.arguments(it) }
        val detail = when {
            values == null -> "example ${index + 1} could not be parsed as an argument list"
            values.size != signature.parameters.size ->
                "example ${index + 1} has ${values.size} argument(s) but solution declares ${signature.parameters.size}"
            example.expected == null ->
                "example ${index + 1}'s expected value was not captured"
            else -> "example ${index + 1} does not fit the parameters"
        }
        return Runner.Refused("$detail — refusing rather than generating a runner that tests the wrong thing")
    }

    /**
     * A JSON value as a Python literal. Nearly verbatim — the divergences are exactly three
     * keywords (`true`/`false`/`null` → `True`/`False`/`None`) and string quoting, applied
     * recursively through arrays.
     */
    private fun literalOf(value: JsonElement): String? = when (value) {
        is JsonNull -> "None"
        is JsonArray -> value.map { literalOf(it) ?: return null }.joinToString(", ", "[", "]")
        is JsonPrimitive -> when {
            value.isString -> quoted(value.content)
            value.booleanOrNull != null -> if (value.booleanOrNull == true) "True" else "False"
            else -> value.content // numbers print as JSON prints them, which Python reads
        }
        else -> null
    }

    private fun quoted(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private fun solutionSource(calls: List<Call>): String {
        val cases = calls.mapIndexed { index, call ->
            "    check(${index + 1}, Solution.solution(${call.arguments}), ${call.expected})"
        }.joinToString("\n")
        return """
# Generated by programmers-tracker from the judge's own examples — do not edit; it is
# replaced after every run. Run it with:  python3 $FILE_NAME  (Solution.py beside it)
import Solution

failures = 0

def check(index, actual, expected):
    global failures
    ok = actual == expected
    print(f"example {index}  " + ("PASS" if ok else f"FAIL — expected {expected!r}, got {actual!r}"))
    if not ok:
        failures += 1

if __name__ == "__main__":
$cases
    print("ALL PASS" if failures == 0 else f"{failures} FAILED")
    if failures:
        raise SystemExit(1)
        """.trimIndent() + "\n"
    }

    // Main-style ----------------------------------------------------------------------------

    private fun stdinRunner(examples: List<ProblemExample>): Runner {
        val cases = examples.mapIndexed { index, example ->
            val stdin = (example.input?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s stdin could not be read as a quoted text")
            val expected = (example.expected?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s expected output could not be read as a quoted text")
            "    check(${index + 1}, ${quoted(stdin)}, ${quoted(expected)})"
        }
        return Runner.Generated(FILE_NAME, stdinSource(cases.joinToString("\n")))
    }

    /**
     * Runs `Solution.py` as a fresh child process per example rather than importing it —
     * a main-style script executes at import time and could only ever run once in-process.
     * The same two normalisations as Java's, and only those: trailing newlines (the judge
     * accepts a println-terminated last line) and `\r\n` → `\n` (the runner runs on the
     * user's platform; the judge's expected output uses `\n` — caught on windows-latest).
     */
    private fun stdinSource(cases: String): String = """
# Generated by programmers-tracker from the judge's own examples — do not edit; it is
# replaced after every run. Run it with:  python3 $FILE_NAME  (Solution.py beside it)
import subprocess
import sys

failures = 0

def check(index, stdin_text, expected):
    global failures
    result = subprocess.run(
        [sys.executable, "Solution.py"], input=stdin_text, capture_output=True, text=True,
    )
    actual = result.stdout.replace("\r\n", "\n").rstrip("\n")
    want = expected.replace("\r\n", "\n").rstrip("\n")
    # A crash after the right output is still a crash: the judge sees exitCode on the
    # run/testcase frame (protocol §7.1), so a runner that read only stdout passed a
    # solution that printed the answer and then died (#98).
    if result.returncode != 0:
        detail = (result.stderr or "").strip().splitlines()
        print(f"example {index}  FAIL — exited {result.returncode}: " + (detail[-1] if detail else "no stderr"))
        failures += 1
        return
    ok = actual == want
    print(f"example {index}  " + ("PASS" if ok else f"FAIL — expected {want!r}, got {actual!r}"))
    if not ok:
        failures += 1

if __name__ == "__main__":
$cases
    print("ALL PASS" if failures == 0 else f"{failures} FAILED")
    if failures:
        raise SystemExit(1)
    """.trimIndent() + "\n"

    private const val FILE_NAME = "runner_test.py"
}
