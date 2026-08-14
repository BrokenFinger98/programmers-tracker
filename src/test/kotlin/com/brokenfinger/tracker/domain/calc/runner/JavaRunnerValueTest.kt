package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every value shape a Java runner has to render, and every one it has to refuse (#272).
 *
 * The execution suites prove a *generated* runner compiles and passes; they say nothing about the
 * shapes nobody wrote a case for, which is why `domain/calc/runner` sits at 66% with the seven of
 * them green. `scalarLiteral` alone carries 13 unexercised branches here — one per type it knows,
 * plus the refusal each of them owes when the example does not match the declared type.
 *
 * **The refusals are the half that matters.** A literal rendered wrong produces a file that either
 * fails to compile — noise — or compiles and tests something other than the problem, which is the
 * silent-wrong-data failure the constitution ranks worst. #212 is the precedent: six of seven
 * languages were being classified by patterns written for two other languages, four of them
 * correctly only by coincidence.
 *
 * Table-driven because the shapes are a matrix, not a narrative, and a missing row should be
 * visible as a gap in a list rather than as an absent paragraph.
 */
class JavaRunnerValueTest {
    @TestFactory
    fun `each declared type renders the literal Java needs`() = listOf(
        Case("int", "7", "solution(7)"),
        Case("long", "7", "solution(7L)"),
        Case("double", "1.5", "solution(1.5)"),
        Case("boolean", "true", "solution(true)"),
        Case("String", "\"ab\"", """solution("ab")"""),
        // Escaping is the point of the quoted() helper, and each of these has broken a generated
        // file in some language at some point: a bare quote ends the literal, a backslash eats the
        // next character, and a raw newline is a syntax error in Java source.
        Case("String", "\"a\\\"b\"", """solution("a\"b")"""),
        Case("String", "\"a\\\\b\"", """solution("a\\b")"""),
        Case("String", "\"a\\nb\"", """solution("a\nb")"""),
        // A quoted number for a numeric parameter is **accepted**, and the asymmetry below is
        // deliberate in the code: the numeric arms read the primitive's value without asking
        // whether it was quoted, while `String` demands `isString`. A quoted number is
        // unambiguously that number; an unquoted number is not a string the judge passed.
        //
        // Never measured. No real `examples.json` and no fixture has ever carried a quoted number
        // for a numeric parameter — pinned here as the behaviour that exists rather than as one
        // anybody chose, so a future reader can see it was looked at.
        Case("int", "\"7\"", "solution(7)"),
        Case("long", "\"7\"", "solution(7L)"),
        Case("double", "\"1.5\"", "solution(1.5)"),
        Case("boolean", "\"true\"", "solution(true)"),
    ).map { case ->
        DynamicTest.dynamicTest("${case.type} ${case.input} → ${case.expectedSource}") {
            val runner = generate(case.type, case.input).shouldBeInstanceOf<Runner.Generated>()

            runner.source shouldContain case.expectedSource
        }
    }

    /**
     * A value the declared type cannot hold. Every one of these must come back [Runner.Refused]
     * with a reason — never a best-effort file, and never a silently coerced value.
     */
    @TestFactory
    fun `a value the declared type cannot hold is refused`() = listOf(
        // Java's `int` is 32 bits and Programmers' examples are not. Coercing this would produce a
        // runner that compiles and tests a different number than the judge did.
        Refusal("int", "3000000000", "an int that does not fit 32 bits"),
        Refusal("int", "1.5", "a double where an int was declared"),
        Refusal("boolean", "1", "a number where a boolean was declared"),
        // The other side of the asymmetry above: `String` checks `isString`, so an unquoted
        // number is refused rather than stringified into an argument nobody passed.
        Refusal("String", "7", "a number where a String was declared"),
        Refusal("String", "null", "a null where a String was declared"),
        Refusal("int", "[1, 2]", "an array where a scalar was declared"),
    ).map { case ->
        DynamicTest.dynamicTest("${case.type} ← ${case.input} (${case.why})") {
            generate(case.type, case.input).shouldBeInstanceOf<Runner.Refused>()
        }
    }

    private fun generate(type: String, input: String): Runner {
        val code = "class Solution { public int solution($type value) { return 0; } }"
        return JavaRunner.generate(code, listOf(ProblemExample(input, "0")))
    }

    private data class Case(val type: String, val input: String, val expectedSource: String)

    private data class Refusal(val type: String, val input: String, val why: String)
}
