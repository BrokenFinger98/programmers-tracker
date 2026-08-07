package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `(user's code, measured examples) → runner_test.js`, or a refusal (#82).
 *
 * Fourth language in the measured support order, and the one with no type mapping to get
 * wrong: §7.1 example values are JSON, and JSON is valid JavaScript, so literals embed
 * verbatim. What JavaScript takes back is loading — the measured skeleton (120803, editor
 * capture 2026-08-07) declares `function solution(...)` and exports nothing, so `require()`
 * cannot see it. The runner loads `Solution.js` as a *script* via `vm.runInThisContext`,
 * where a function declaration lands on the global object; the declaration-only rule in
 * [JavascriptSignature] exists because a `const solution = ...` would stay script-scoped
 * and the generated call would throw.
 *
 * Main-style (181951: top-level `readline` over `process.stdin`) runs at load, so like
 * Python it is re-run as a fresh `node` child per example.
 */
object JavascriptRunner {
    fun generate(code: String, examples: List<ProblemExample>): Runner {
        if (examples.isEmpty()) {
            return Runner.Refused("no examples were captured — press Run Code (코드 실행) once and they will be")
        }
        return when (ProblemShape.ofJavascript(code)) {
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
        val signature = JavascriptSignature.of(code)
            ?: return Runner.Refused(
                "the solution declaration could not be read — only the skeleton's " +
                    "function solution(...) form is supported (an assigned arrow stays " +
                    "invisible to the script loader), and rest/default parameters are not",
            )
        val calls = examples.mapIndexed { index, example ->
            callFor(index, example, signature) ?: return refusalFor(index, example, signature)
        }
        return Runner.Generated(FILE_NAME, solutionSource(calls))
    }

    private data class Call(val arguments: String, val expected: String)

    private fun callFor(index: Int, example: ProblemExample, signature: JavascriptSignature): Call? {
        val raw = example.input ?: return null
        val values = ExampleValues.arguments(raw) ?: return null
        if (values.size != signature.parameters.size) return null
        // JSON is JavaScript: every parsed value serialises back to a valid JS literal.
        val arguments = values.joinToString(", ") { it.toString() }
        val expected = example.expected?.let { ExampleValues.single(it) }?.toString()
        return Call(arguments, expected ?: "null /* expected value not captured */")
    }

    private fun refusalFor(index: Int, example: ProblemExample, signature: JavascriptSignature): Runner.Refused {
        val values = example.input?.let { ExampleValues.arguments(it) }
        val detail = when {
            values == null -> "example ${index + 1} could not be parsed as an argument list"
            values.size != signature.parameters.size ->
                "example ${index + 1} has ${values.size} argument(s) but solution declares ${signature.parameters.size}"
            else -> "example ${index + 1} does not fit the parameters"
        }
        return Runner.Refused("$detail — refusing rather than generating a runner that tests the wrong thing")
    }

    private fun solutionSource(calls: List<Call>): String {
        val cases = calls.mapIndexed { index, call ->
            "check(${index + 1}, solution(${call.arguments}), ${call.expected});"
        }.joinToString("\n")
        return """
$HEADER
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");

// Solution.js exports nothing (the measured skeleton is a bare declaration), so it is
// loaded as a script: a function declaration lands on the global object.
vm.runInThisContext(fs.readFileSync(path.join(__dirname, "Solution.js"), "utf8"), { filename: "Solution.js" });

let failures = 0;

function check(index, actual, expected) {
    const ok = JSON.stringify(actual) === JSON.stringify(expected);
    const detail = "  FAIL — expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual);
    console.log("example " + index + (ok ? "  PASS" : detail));
    if (!ok) failures += 1;
}

$cases
$FOOTER
        """.trimIndent() + "\n"
    }

    // Main-style ----------------------------------------------------------------------------

    /**
     * Top-level code runs at load and could only ever run once in-process, so each example
     * spawns a fresh `node` child — `process.execPath`, the running node itself, like
     * Python's `sys.executable`. The same two normalisations as every runner, and only
     * those: trailing newlines and `\r\n` → `\n`.
     */
    private fun stdinRunner(examples: List<ProblemExample>): Runner {
        val cases = examples.mapIndexed { index, example ->
            val stdin = (example.input?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s stdin could not be read as a quoted text")
            val expected = (example.expected?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s expected output could not be read as a quoted text")
            "check(${index + 1}, ${quoted(stdin)}, ${quoted(expected)});"
        }
        return Runner.Generated(FILE_NAME, stdinSource(cases.joinToString("\n")))
    }

    private fun quoted(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private fun stdinSource(cases: String): String = """
$HEADER
"use strict";
const { spawnSync } = require("child_process");
const path = require("path");

let failures = 0;

function check(index, stdinText, expected) {
    const result = spawnSync(process.execPath, [path.join(__dirname, "Solution.js")], {
        input: stdinText,
        encoding: "utf8",
    });
    // Two normalisations, and only these two: trailing newlines and \r\n to \n.
    const actual = String(result.stdout || "").replace(/\r\n/g, "\n").replace(/\n+$/, "");
    const want = expected.replace(/\r\n/g, "\n").replace(/\n+$/, "");
    const ok = actual === want;
    console.log("example " + index + (ok ? "  PASS" : "  FAIL — expected <" + want + ">, got <" + actual + ">"));
    if (!ok) failures += 1;
}

$cases
$FOOTER
    """.trimIndent() + "\n"

    private const val FILE_NAME = "runner_test.js"

    private const val HEADER =
        "// Generated by programmers-tracker from the judge's own examples — do not edit; it is\n" +
            "// replaced after every run. Run it with:  node runner_test.js  (Solution.js beside it)"

    private const val FOOTER =
        "console.log(failures === 0 ? \"ALL PASS\" : failures + \" FAILED\");\n" +
            "if (failures > 0) process.exit(1);"
}
