package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The C++ `solution(...)` declaration parser (#80). Anchored on the skeletons measured from
 * the actual Programmers editor on 2026-08-07 — 120803 (scalars), 120817 (`vector<int>`,
 * `double` return), 12950 (`vector<vector<int>>` spelled without a space) — all by-value,
 * all under `using namespace std;`.
 */
class CppSignatureTest {
    /** Measured skeleton, 120803. */
    @Test
    fun `reads the measured scalar skeleton`() {
        val code = """
            #include <string>
            #include <vector>

            using namespace std;

            int solution(int num1, int num2) {
                int answer = 0;
                return answer;
            }
        """.trimIndent()

        CppSignature.of(code) shouldBe CppSignature(
            "int",
            listOf(CppParameter("int", "num1"), CppParameter("int", "num2")),
        )
    }

    /** Measured skeleton, 120817. */
    @Test
    fun `reads the measured vector skeleton`() {
        val code = "double solution(vector<int> numbers) { return 0; }"

        CppSignature.of(code) shouldBe CppSignature("double", listOf(CppParameter("vector<int>", "numbers")))
    }

    /** Measured skeleton, 12950 — nested vectors, `>>` without a space. */
    @Test
    fun `reads the measured nested vector skeleton`() {
        val code = "vector<vector<int>> solution(vector<vector<int>> arr1, vector<vector<int>> arr2) {}"

        CppSignature.of(code) shouldBe CppSignature(
            "vector<vector<int>>",
            listOf(CppParameter("vector<vector<int>>", "arr1"), CppParameter("vector<vector<int>>", "arr2")),
        )
    }

    @Test
    fun `reads long long which is the one two-word type`() {
        val code = "long long solution(long long n) { return n; }"

        CppSignature.of(code) shouldBe CppSignature("long long", listOf(CppParameter("long long", "n")))
    }

    /**
     * The measured skeletons pass by value, but adding `const ... &` (or a bare `&`) is a
     * common hand-edit. Both bind, because the runner passes **named locals**, never
     * temporaries — that is why the generator emits `int arg1 = 6;` instead of inlining
     * literals. The type is recorded bare.
     */
    @Test
    fun `accepts reference parameters and records the bare type`() {
        val constRef = "int solution(const vector<int>& numbers) { return 0; }"
        val mutableRef = "int solution(vector<int>& numbers) { return 0; }"

        CppSignature.of(constRef) shouldBe CppSignature("int", listOf(CppParameter("vector<int>", "numbers")))
        CppSignature.of(mutableRef) shouldBe CppSignature("int", listOf(CppParameter("vector<int>", "numbers")))
    }

    /** `using namespace std;` is in every measured skeleton, but `std::` spelling still occurs. */
    @Test
    fun `strips std prefixes`() {
        val code = "std::vector<int> solution(std::vector<std::string> words) { return {}; }"

        CppSignature.of(code) shouldBe CppSignature(
            "vector<int>",
            listOf(CppParameter("vector<string>", "words")),
        )
    }

    @Test
    fun `finds the declaration even after a call site of the same name`() {
        val code = """
            int helper(int a) { return solution(a); }
            int solution(int a) { return a; }
        """.trimIndent()

        CppSignature.of(code) shouldBe CppSignature("int", listOf(CppParameter("int", "a")))
    }

    @Test
    fun `an empty parameter list is read as such`() {
        CppSignature.of("int solution() { return 0; }") shouldBe CppSignature("int", emptyList())
    }

    // Refusals ------------------------------------------------------------------------------

    @Test
    fun `refuses an unmeasured parameter type`() {
        CppSignature.of("int solution(map<int, int> counts) { return 0; }").shouldBeNull()
    }

    @Test
    fun `refuses an unmeasured return type`() {
        CppSignature.of("auto solution(int n) { return n; }").shouldBeNull()
    }

    @Test
    fun `refuses code with a call but no declaration`() {
        CppSignature.of("int run() { return solution(1); }").shouldBeNull()
    }
}
