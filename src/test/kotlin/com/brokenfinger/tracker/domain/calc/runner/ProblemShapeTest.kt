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

    @Test
    fun `neither shape is refused, not guessed`() {
        ProblemShape.of("SELECT * FROM FOODS_INFO;") shouldBe ProblemShape.UNRECOGNISED
    }

    @Test
    fun `blank code is refused`() {
        ProblemShape.of("   ") shouldBe ProblemShape.UNRECOGNISED
    }
}
