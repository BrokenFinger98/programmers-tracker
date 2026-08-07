package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which of the two measured problem shapes a solution is (protocol §7.1) — the first
 * question the runner generator asks, answered from the user's own code because the code is
 * what the runner has to match.
 *
 * The default is refusal: a file that matches neither shape gets [ProblemShape.UNRECOGNISED],
 * and the generator declines. Guessing here is how a runner ends up feeding stdin to a
 * `solution(...)` problem — compiling, running, and testing the wrong thing.
 */
class ProblemShapeTest {
    @Test
    fun `a solution method makes it solution-style`() {
        val code = """
            class Solution {
                public int solution(int num1, int num2) {
                    return num1 * num2;
                }
            }
        """.trimIndent()

        ProblemShape.of(code) shouldBe ProblemShape.SOLUTION_FUNCTION
    }

    /** Measured on 250133/181951: main-style ships a `main` and reads stdin. */
    @Test
    fun `a main method reading stdin makes it main-style`() {
        val code = """
            import java.util.Scanner;
            public class Solution {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                }
            }
        """.trimIndent()

        ProblemShape.of(code) shouldBe ProblemShape.STDIN_MAIN
    }

    /**
     * Both at once is main-style: Programmers' main-style skeletons name the class
     * `Solution` too, and a helper called `solution` inside a `main` program is the user's
     * own refactoring, not a harness entry point. The `main` is what actually runs.
     */
    @Test
    fun `a main method wins over a solution helper`() {
        val code = """
            public class Solution {
                static int solution(int n) { return n; }
                public static void main(String[] args) { System.out.println(solution(1)); }
            }
        """.trimIndent()

        ProblemShape.of(code) shouldBe ProblemShape.STDIN_MAIN
    }

    // Python -------------------------------------------------------------------------------

    @Test
    fun `a python def solution makes it solution-style`() {
        ProblemShape.ofPython("def solution(num1, num2):\n    return num1 * num2") shouldBe
            ProblemShape.SOLUTION_FUNCTION
    }

    /** Python has no main: the main-style skeleton is top-level code reading input(). */
    @Test
    fun `python top-level input reading makes it main-style`() {
        ProblemShape.ofPython("a, b = map(int, input().split())\nprint(a + b)") shouldBe
            ProblemShape.STDIN_MAIN
    }

    /** Reversed priority from Java, and the doc says why. */
    @Test
    fun `a python def solution wins over an input call inside it`() {
        val code = "def solution(prompt):\n    return input(prompt)"

        ProblemShape.ofPython(code) shouldBe ProblemShape.SOLUTION_FUNCTION
    }

    @Test
    fun `python code with neither is refused`() {
        ProblemShape.ofPython("print(1 + 1)") shouldBe ProblemShape.UNRECOGNISED
    }

    // C++ ----------------------------------------------------------------------------------

    /** Measured skeleton, 120803 (editor capture 2026-08-07). */
    @Test
    fun `a cpp solution function makes it solution-style`() {
        val code = """
            #include <string>
            #include <vector>

            using namespace std;

            int solution(int num1, int num2) {
                int answer = 0;
                return answer;
            }
        """.trimIndent()

        ProblemShape.ofCpp(code) shouldBe ProblemShape.SOLUTION_FUNCTION
    }

    /** Measured skeleton, 181951 (editor capture 2026-08-07) — `int main(void)`. */
    @Test
    fun `a cpp main makes it main-style`() {
        val code = """
            #include <iostream>

            using namespace std;

            int main(void) {
                int a;
                int b;
                cin >> a >> b;
                cout << a + b << endl;
                return 0;
            }
        """.trimIndent()

        ProblemShape.ofCpp(code) shouldBe ProblemShape.STDIN_MAIN
    }

    /** Same priority as Java, same reason: a `solution` helper inside a `main` program is the user's own. */
    @Test
    fun `a cpp main wins over a solution helper`() {
        val code = """
            int solution(int n) { return n; }
            int main(void) { std::cout << solution(1); return 0; }
        """.trimIndent()

        ProblemShape.ofCpp(code) shouldBe ProblemShape.STDIN_MAIN
    }

    @Test
    fun `cpp code with neither is refused`() {
        ProblemShape.ofCpp("void helper() {}") shouldBe ProblemShape.UNRECOGNISED
    }

    @Test
    fun `neither shape is refused, not guessed`() {
        ProblemShape.of("SELECT * FROM FOODS_INFO;") shouldBe ProblemShape.UNRECOGNISED
    }

    @Test
    fun `blank code is refused`() {
        ProblemShape.of("   ") shouldBe ProblemShape.UNRECOGNISED
    }
}
