package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Kotlin `solution(...)` declaration parser (#84). Anchored on the skeletons measured
 * from the actual editor (2026-08-07): 120803 (`Int` scalars), 120817 (`IntArray` — the
 * primitive array, not `Array<Int>` or `List<Int>`), 12950 (`Array<IntArray>`), all inside
 * `class Solution`.
 */
class KotlinSignatureTest {
    /** Measured skeleton, 120803. */
    @Test
    fun `reads the measured scalar skeleton`() {
        val code = """
            class Solution {
                fun solution(num1: Int, num2: Int): Int {
                    var answer: Int = 0
                    return answer
                }
            }
        """.trimIndent()

        KotlinSignature.of(code) shouldBe KotlinSignature(
            "Int",
            listOf(KotlinParameter("num1", "Int"), KotlinParameter("num2", "Int")),
        )
    }

    /** Measured skeleton, 120817 — the 1-D int parameter is the primitive IntArray. */
    @Test
    fun `reads the measured primitive array skeleton`() {
        val code = "class Solution {\n    fun solution(numbers: IntArray): Double {\n" +
            "        var answer: Double = 0\n        return answer\n    }\n}"

        KotlinSignature.of(code) shouldBe
            KotlinSignature("Double", listOf(KotlinParameter("numbers", "IntArray")))
    }

    /** Measured skeleton, 12950. */
    @Test
    fun `reads the measured nested array skeleton`() {
        val code = "class Solution {\n" +
            "    fun solution(arr1: Array<IntArray>, arr2: Array<IntArray>): Array<IntArray> {\n" +
            "        var answer = arrayOf<IntArray>()\n        return answer\n    }\n}"

        KotlinSignature.of(code) shouldBe KotlinSignature(
            "Array<IntArray>",
            listOf(KotlinParameter("arr1", "Array<IntArray>"), KotlinParameter("arr2", "Array<IntArray>")),
        )
    }

    @Test
    fun `reads a string array parameter`() {
        val code = "class Solution {\n    fun solution(words: Array<String>): String {\n" +
            "        return \"\"\n    }\n}"

        KotlinSignature.of(code) shouldBe
            KotlinSignature("String", listOf(KotlinParameter("words", "Array<String>")))
    }

    // Refusals ------------------------------------------------------------------------------

    /** The measured spelling is IntArray; the boxed Array<Int> is unmeasured. */
    @Test
    fun `refuses a boxed integer array as unmeasured`() {
        KotlinSignature.of("fun solution(xs: Array<Int>): Int { return 0 }").shouldBeNull()
    }

    @Test
    fun `refuses a list parameter as unmeasured`() {
        KotlinSignature.of("fun solution(xs: List<Int>): Int { return 0 }").shouldBeNull()
    }

    /**
     * An expression-body solution declares no return type, and the declared type is the
     * only honest source for building the expected value — JSON cannot tell Int from Long.
     */
    @Test
    fun `refuses an expression body without a declared return type`() {
        KotlinSignature.of("class Solution {\n    fun solution(a: Int) = a * 2\n}").shouldBeNull()
    }

    @Test
    fun `refuses code without a solution declaration`() {
        KotlinSignature.of("fun helper(a: Int): Int = a").shouldBeNull()
    }
}
