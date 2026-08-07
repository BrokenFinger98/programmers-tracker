package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The JavaScript `solution(...)` declaration parser (#82). Names and arity like Python's —
 * JavaScript declares no types either. Anchored on the skeleton measured from the actual
 * editor (120803, 2026-08-07): a plain `function solution(num1, num2)` declaration,
 * nothing exported.
 *
 * Only the declaration form is admitted: the runner loads `Solution.js` as a script, where
 * a `function` declaration lands on the global object but a `const solution = ...` arrow
 * stays script-scoped — parsing the latter would generate a runner that cannot see the
 * function it tests.
 */
class JavascriptSignatureTest {
    /** Measured skeleton, 120803. */
    @Test
    fun `reads the measured skeleton`() {
        val code = "function solution(num1, num2) {\n    var answer = 0;\n    return answer;\n}"

        JavascriptSignature.of(code) shouldBe JavascriptSignature(listOf("num1", "num2"))
    }

    @Test
    fun `an empty parameter list is read as such`() {
        JavascriptSignature.of("function solution() { return 42; }") shouldBe
            JavascriptSignature(emptyList())
    }

    @Test
    fun `refuses a rest parameter because the arity check would be meaningless`() {
        JavascriptSignature.of("function solution(...args) { return 0; }").shouldBeNull()
    }

    @Test
    fun `refuses a default value as an unmeasured shape`() {
        JavascriptSignature.of("function solution(a, b = 1) { return a; }").shouldBeNull()
    }

    @Test
    fun `refuses an arrow assignment the script loader could not see`() {
        JavascriptSignature.of("const solution = (a, b) => a + b;").shouldBeNull()
    }

    @Test
    fun `refuses code without a solution declaration`() {
        JavascriptSignature.of("function helper(a) { return a; }").shouldBeNull()
    }
}
