package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `def solution(...)` — names and arity, and deliberately nothing more, because Python
 * declares nothing more. That is one check fewer than Java's signature gives us: arity can
 * still refuse a mismatched example, types cannot, and the generator must not pretend
 * otherwise (#78).
 *
 * Unmeasured shapes — decorators, `*args`, defaults — are refused, not half-parsed. The
 * Programmers Python skeletons declare a bare `def solution(a, b):`.
 */
class PythonSignatureTest {
    @Test
    fun `parses the measured skeleton`() {
        val sig = PythonSignature.of("def solution(num1, num2):\n    answer = 0\n    return answer")!!

        sig.parameters shouldBe listOf("num1", "num2")
    }

    @Test
    fun `a no-argument solution parses to an empty list`() {
        PythonSignature.of("def solution():\n    return 1")!!.parameters shouldBe emptyList()
    }

    @Test
    fun `type hints are tolerated, because they change nothing we use`() {
        val sig = PythonSignature.of("def solution(a: int, b: int) -> int:\n    return a * b")!!

        sig.parameters shouldBe listOf("a", "b")
    }

    @Test
    fun `no solution function means null`() {
        PythonSignature.of("print(input())").shouldBeNull()
    }

    @Test
    fun `varargs are refused rather than guessed at`() {
        PythonSignature.of("def solution(*args):\n    return 0").shouldBeNull()
    }

    @Test
    fun `default values are refused — an example may or may not supply them`() {
        PythonSignature.of("def solution(a, b=3):\n    return a").shouldBeNull()
    }
}
