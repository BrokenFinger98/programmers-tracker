package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The C `solution(...)` declaration parser (#86). C is the language where **one wire value
 * is not one parameter**: the measured skeletons (editor captures 2026-08-07) pass a 1-D
 * array as `int name[]` plus `size_t name_len` (120817/120821), and a 2-D array as
 * `int** name` plus `size_t name_rows` plus `size_t name_cols` (120860). The parser groups
 * those physical parameters into logical ones, and the wire arity check counts the logical
 * side. Strings travel as `const char*` in and malloc'd `char*` out (120822).
 */
class CSignatureTest {
    /** Measured skeleton, 120803. */
    @Test
    fun `reads the measured scalar skeleton`() {
        val code = """
            #include <stdio.h>
            #include <stdbool.h>
            #include <stdlib.h>

            int solution(int num1, int num2) {
                int answer = 0;
                return answer;
            }
        """.trimIndent()

        CSignature.of(code) shouldBe CSignature(
            CScalarReturn("int"),
            listOf(CScalarParam("num1", "int"), CScalarParam("num2", "int")),
        )
    }

    /** Measured skeleton, 120817 — `int numbers[]` + `size_t numbers_len` is ONE logical parameter. */
    @Test
    fun `groups an array and its length into one logical parameter`() {
        val code = "double solution(int numbers[], size_t numbers_len) { return 0; }"

        CSignature.of(code) shouldBe
            CSignature(CScalarReturn("double"), listOf(CIntArrayParam("numbers")))
    }

    /** Measured skeleton, 120860 — `int** dots` + `_rows` + `_cols` is ONE logical parameter. */
    @Test
    fun `groups a grid and its two sizes into one logical parameter`() {
        val code = "int solution(int** dots, size_t dots_rows, size_t dots_cols) { return 0; }"

        CSignature.of(code) shouldBe
            CSignature(CScalarReturn("int"), listOf(CIntGridParam("dots")))
    }

    /** Measured skeleton, 120822 — string in as `const char*`, out as malloc'd `char*`. */
    @Test
    fun `reads the measured string skeleton`() {
        val code = "char* solution(const char* my_string) {\n" +
            "    char* answer = (char*)malloc(1);\n    return answer;\n}"

        CSignature.of(code) shouldBe CSignature(CTextReturn, listOf(CTextParam("my_string")))
    }

    /** Measured skeleton, 120821 — a 1-D return is a malloc'd `int*`. */
    @Test
    fun `reads the measured array-returning skeleton`() {
        val code = "int* solution(int num_list[], size_t num_list_len) {\n" +
            "    int* answer = (int*)malloc(1);\n    return answer;\n}"

        CSignature.of(code) shouldBe CSignature(CIntArrayReturn, listOf(CIntArrayParam("num_list")))
    }

    @Test
    fun `star placement does not matter`() {
        val code = "char *solution(const char *s) { return 0; }"

        CSignature.of(code) shouldBe CSignature(CTextReturn, listOf(CTextParam("s")))
    }

    // Refusals ------------------------------------------------------------------------------

    /** A `size_t` with no array before it fits no measured pattern. */
    @Test
    fun `refuses an unpaired length parameter`() {
        CSignature.of("int solution(size_t n) { return 0; }").shouldBeNull()
    }

    /** The measured convention names the length `<array>_len`; anything else is not it. */
    @Test
    fun `refuses a length parameter with the wrong name`() {
        CSignature.of("int solution(int xs[], size_t count) { return 0; }").shouldBeNull()
    }

    @Test
    fun `refuses a grid missing one of its two sizes`() {
        CSignature.of("int solution(int** g, size_t g_rows) { return 0; }").shouldBeNull()
    }

    /** Unmeasured: no captured skeleton passes an array of strings. */
    @Test
    fun `refuses a string array as unmeasured`() {
        CSignature.of("int solution(char* words[], size_t words_len) { return 0; }").shouldBeNull()
    }

    /** Unmeasured: captured arrays are int; a double array skeleton has not been seen. */
    @Test
    fun `refuses a non-int array element as unmeasured`() {
        CSignature.of("double solution(double xs[], size_t xs_len) { return 0; }").shouldBeNull()
    }

    @Test
    fun `refuses a grid return as unmeasured`() {
        CSignature.of("int** solution(int** g, size_t g_rows, size_t g_cols) { return 0; }").shouldBeNull()
    }

    @Test
    fun `refuses code with a call but no declaration`() {
        CSignature.of("int run(void) { return solution(1); }").shouldBeNull()
    }
}
