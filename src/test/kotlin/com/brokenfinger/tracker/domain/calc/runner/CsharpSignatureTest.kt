package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The C# `solution(...)` declaration parser (#88). Anchored on the skeletons measured from
 * the actual editor (2026-08-07, title-guarded): scalars (120803), `int[]` with no length
 * parameters (120817), the rectangular `int[,]` (120860), `string` (120822).
 */
class CsharpSignatureTest {
    /** Measured skeleton, 120803. */
    @Test
    fun `reads the measured scalar skeleton`() {
        val code = """
            using System;

            public class Solution {
                public int solution(int num1, int num2) {
                    int answer = 0;
                    return answer;
                }
            }
        """.trimIndent()

        CsharpSignature.of(code) shouldBe CsharpSignature(
            "int",
            listOf(CsharpParameter("int", "num1"), CsharpParameter("int", "num2")),
        )
    }

    /** Measured skeleton, 120817 — `int[]`, and no length parameters (arrays carry their own). */
    @Test
    fun `reads the measured array skeleton`() {
        val code = "public class Solution {\n    public double solution(int[] numbers) {\n" +
            "        return 0;\n    }\n}"

        CsharpSignature.of(code) shouldBe
            CsharpSignature("double", listOf(CsharpParameter("int[]", "numbers")))
    }

    /** Measured skeleton, 120860 — the 2-D type is the rectangular `int[,]`, comma and all. */
    @Test
    fun `reads the measured grid skeleton without splitting its comma`() {
        val code = "public class Solution {\n    public int solution(int[,] dots) {\n" +
            "        return 0;\n    }\n}"

        CsharpSignature.of(code) shouldBe
            CsharpSignature("int", listOf(CsharpParameter("int[,]", "dots")))
    }

    /** Two parameters where one is `int[,]` — the split must respect the bracketed comma. */
    @Test
    fun `separates parameters only at top-level commas`() {
        val code = "public class Solution {\n    public int solution(int[,] grid, int k) {\n" +
            "        return 0;\n    }\n}"

        CsharpSignature.of(code) shouldBe CsharpSignature(
            "int",
            listOf(CsharpParameter("int[,]", "grid"), CsharpParameter("int", "k")),
        )
    }

    /** Measured skeleton, 120822. */
    @Test
    fun `reads the measured string skeleton`() {
        val code = "public class Solution {\n    public string solution(string my_string) {\n" +
            "        return \"\";\n    }\n}"

        CsharpSignature.of(code) shouldBe
            CsharpSignature("string", listOf(CsharpParameter("string", "my_string")))
    }

    // Refusals ------------------------------------------------------------------------------

    /** Only `int[,]` is measured; other rectangular kinds refuse. */
    @Test
    fun `refuses an unmeasured rectangular kind`() {
        CsharpSignature.of("public class Solution { public int solution(double[,] g) { return 0; } }")
            .shouldBeNull()
    }

    @Test
    fun `refuses a jagged array type as unmeasured`() {
        CsharpSignature.of("public class Solution { public int solution(int[][] g) { return 0; } }")
            .shouldBeNull()
    }

    @Test
    fun `refuses a list parameter as unmeasured`() {
        CsharpSignature.of("public class Solution { public int solution(List<int> xs) { return 0; } }")
            .shouldBeNull()
    }

    @Test
    fun `refuses code without a public solution declaration`() {
        CsharpSignature.of("public class Solution { private int helper(int a) { return a; } }")
            .shouldBeNull()
    }
}
