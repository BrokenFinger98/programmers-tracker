package com.brokenfinger.tracker.domain.calc.runner

/**
 * Which of the two measured problem shapes a solution is (protocol §7.1), answered from the
 * user's own code — the code is what the runner has to match, and the statement's rendering
 * is a weaker signal that lives on a page we may not have.
 *
 * `main` wins when both appear: Programmers' main-style skeletons also name the class
 * `Solution`, and a helper called `solution` inside a `main` program is the user's own
 * refactoring rather than a harness entry point. The `main` is what actually runs.
 */
enum class ProblemShape {
    /** Programmers wraps it in its own harness and calls `solution(...)` with arguments. */
    SOLUTION_FUNCTION,

    /** The program owns `main` and reads stdin; the judge feeds input and compares stdout. */
    STDIN_MAIN,

    /** Neither measured shape. The generator refuses rather than guesses. */
    UNRECOGNISED,

    /**
     * Both shapes' signals at once — a `main` **and** a `solution(...)` declaration. The
     * code alone cannot separate the two readings (#91): a main-style problem whose author
     * refactored a helper out, and a solution-style problem with a debug `main` left in,
     * look identical here. Guessing `main` silently generates a harness that never calls
     * the code the judge actually grades, so this refuses instead.
     *
     * Only the five languages that declare a `main` reach this. Python and JavaScript
     * resolve the same collision in favour of the solution declaration, which is the
     * measured rule for them — so a main-style script of theirs that happens to define a
     * `solution` helper carries the mirror-image risk. Unmeasured, and left alone rather
     * than guessed at from the other languages' behaviour.
     */
    AMBIGUOUS,
    ;

    companion object {
        private val JAVA_MAIN = Regex("""public\s+static\s+void\s+main\s*\(""")
        private val SOLUTION_TOKEN = Regex("""\bsolution\s*\(""")
        private val PYTHON_SOLUTION = Regex("""def\s+solution\s*\(""")
        private val PYTHON_STDIN = Regex("""\binput\s*\(|sys\.stdin""")
        private val CPP_MAIN = Regex("""\bint\s+main\s*\(""")
        private val JS_SOLUTION = Regex("""function\s+solution\s*\(""")
        private val JS_STDIN = Regex("""process\.stdin|require\s*\(\s*['"]readline['"]""")
        private val KOTLIN_MAIN = Regex("""fun\s+main\s*\(""")
        private val CSHARP_MAIN = Regex("""static\s+void\s+Main\s*\(""")

        fun of(code: String): ProblemShape = ofJava(code)

        /**
         * Java: a `main` means main-style — **unless** the file also declares `solution`,
         * which is [AMBIGUOUS]. The old rule let `main` win outright, and a debug `main`
         * left in a solution-style file then produced a harness that never called
         * `solution` at all and still printed `ALL PASS` (#91).
         *
         * "Declares" is the language's own signature parser, not the bare token: a `main`
         * program *calling* a helper named `solution` is unambiguous and still reads as
         * main-style, which is what the original rule was reaching for.
         */
        fun ofJava(code: String): ProblemShape = decide(
            main = JAVA_MAIN.containsMatchIn(code),
            declaresSolution = JavaSignature.of(code) != null,
            solutionToken = SOLUTION_TOKEN.containsMatchIn(code),
        )

        /**
         * `main` wins, a solution declaration alongside it is ambiguous, and a bare
         * `solution(` token with no `main` is solution-style — shared by every language
         * whose measured main-style skeleton declares a `main`.
         */
        private fun decide(main: Boolean, declaresSolution: Boolean, solutionToken: Boolean): ProblemShape = when {
            main && declaresSolution -> AMBIGUOUS
            main -> STDIN_MAIN
            solutionToken -> SOLUTION_FUNCTION
            else -> UNRECOGNISED
        }

        /**
         * C++: `main` wins, Java's priority for Java's reason. Measured skeletons (editor
         * capture 2026-08-07): 181951 ships `int main(void)`; the solution-style skeletons
         * (120803, 120817, 12950) never declare a `main`.
         */
        fun ofCpp(code: String): ProblemShape = decide(
            main = CPP_MAIN.containsMatchIn(code),
            declaresSolution = CppSignature.of(code) != null,
            solutionToken = SOLUTION_TOKEN.containsMatchIn(code),
        )

        /**
         * C: identical signals to C++ — the measured skeletons (editor captures
         * 2026-08-07) share `int main(void)` on the main side and a `solution(`
         * declaration on the other, so the same rule reads both languages.
         */
        fun ofC(code: String): ProblemShape = decide(
            main = CPP_MAIN.containsMatchIn(code),
            declaresSolution = CSignature.of(code) != null,
            solutionToken = SOLUTION_TOKEN.containsMatchIn(code),
        )

        /**
         * JavaScript: Python's reversed priority, Python's reason — the solution skeleton
         * (120803) always declares `function solution(`, while the main-style skeleton
         * (181951) is top-level code over `process.stdin` via `require('readline')` and
         * never does. Both measured from the editor 2026-08-07.
         */
        fun ofJavascript(code: String): ProblemShape = when {
            JS_SOLUTION.containsMatchIn(code) -> SOLUTION_FUNCTION
            JS_STDIN.containsMatchIn(code) -> STDIN_MAIN
            else -> UNRECOGNISED
        }

        /**
         * Kotlin: `main` wins, Java's priority for Java's reason. Measured skeletons
         * (editor capture 2026-08-07): 181951 ships a top-level `fun main(args:
         * Array<String>)`; the solution-style skeletons (120803, 120817, 12950) declare
         * `fun solution` inside `class Solution` and never a `main`.
         */
        fun ofKotlin(code: String): ProblemShape = decide(
            main = KOTLIN_MAIN.containsMatchIn(code),
            declaresSolution = KotlinSignature.of(code) != null,
            solutionToken = SOLUTION_TOKEN.containsMatchIn(code),
        )

        /**
         * C#: `Main` wins, Java's priority for Java's reason. Measured skeletons (editor
         * captures 2026-08-07): the main-style one declares `public static void Main()` on
         * a class named `Example`; the solution-style ones declare `solution` on
         * `public class Solution` and never a `Main`.
         */
        fun ofCsharp(code: String): ProblemShape = decide(
            main = CSHARP_MAIN.containsMatchIn(code),
            declaresSolution = CsharpSignature.of(code) != null,
            solutionToken = SOLUTION_TOKEN.containsMatchIn(code),
        )

        /**
         * Python has no `main`: a main-style script is top-level code reading `input()`. The
         * priority is reversed from Java's — `def solution(` wins — because the solution-style
         * skeleton always declares it while the main-style skeleton never does, and top-level
         * statements exist in both. A user-defined `solution` helper inside a main-style
         * script is unmeasured; if it happens, the generated caller refuses downstream on
         * arity, which is the honest failure.
         */
        fun ofPython(code: String): ProblemShape = when {
            PYTHON_SOLUTION.containsMatchIn(code) -> SOLUTION_FUNCTION
            PYTHON_STDIN.containsMatchIn(code) -> STDIN_MAIN
            else -> UNRECOGNISED
        }
    }
}
