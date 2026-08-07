package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The user's own `solution(...)` declaration — the only trustworthy source of argument
 * types. The wire sends `"3, 2"` with neither names nor arity (protocol §7.1); JSON cannot
 * tell an `int` from a `long` from a `double` disguised as `3`; the signature can.
 *
 * Parsing is deliberately shallow: the measured skeletons declare one public `solution`
 * method with simple parameter types. Anything fancier — generics, varargs, annotations on
 * parameters — returns null, and the generator refuses. Shallow-and-refusing beats a Java
 * parser that is wrong about edge cases silently.
 */
class JavaSignatureTest {
    @Test
    fun `parses the measured two-int skeleton`() {
        val code = "class Solution { public int solution(int num1, int num2) { return 0; } }"

        val sig = JavaSignature.of(code)!!

        sig.returnType shouldBe "int"
        sig.parameters shouldBe listOf(JavaParameter("int", "num1"), JavaParameter("int", "num2"))
    }

    @Test
    fun `parses arrays and two-dimensional arrays`() {
        val code = "class Solution { public int[] solution(int m, int n, int[][] balls) { return null; } }"

        val sig = JavaSignature.of(code)!!

        sig.returnType shouldBe "int[]"
        sig.parameters.last() shouldBe JavaParameter("int[][]", "balls")
    }

    @Test
    fun `parses String parameters`() {
        val code = """class Solution { public String solution(String s, int n) { return ""; } }"""

        JavaSignature.of(code)!!.parameters.first() shouldBe JavaParameter("String", "s")
    }

    @Test
    fun `a no-argument solution parses to an empty list`() {
        val code = "class Solution { public int solution() { return 1; } }"

        JavaSignature.of(code)!!.parameters shouldBe emptyList()
    }

    @Test
    fun `no solution method means null, and the generator refuses downstream`() {
        JavaSignature.of("public class Solution { public static void main(String[] a) {} }").shouldBeNull()
    }

    /** Shallow on purpose: what the measured skeletons never contain is refused, not parsed. */
    @Test
    fun `generics are refused rather than half-parsed`() {
        val code = "class Solution { public List<Integer> solution(List<String> xs) { return null; } }"

        JavaSignature.of(code).shouldBeNull()
    }
}
