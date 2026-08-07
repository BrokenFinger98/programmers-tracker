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

        fun of(code: String): ProblemShape = ofJava(code)

        /** Java: `main` wins — a `solution` helper inside a `main` program is the user's own. */
        fun ofJava(code: String): ProblemShape = when {
            JAVA_MAIN.containsMatchIn(code) -> STDIN_MAIN
            SOLUTION_TOKEN.containsMatchIn(code) -> SOLUTION_FUNCTION
            else -> UNRECOGNISED
        }

        /**
         * C++: `main` wins, Java's priority for Java's reason. Measured skeletons (editor
         * capture 2026-08-07): 181951 ships `int main(void)`; the solution-style skeletons
         * (120803, 120817, 12950) never declare a `main`.
         */
        fun ofCpp(code: String): ProblemShape = when {
            CPP_MAIN.containsMatchIn(code) -> STDIN_MAIN
            SOLUTION_TOKEN.containsMatchIn(code) -> SOLUTION_FUNCTION
            else -> UNRECOGNISED
        }

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
        fun ofKotlin(code: String): ProblemShape = when {
            KOTLIN_MAIN.containsMatchIn(code) -> STDIN_MAIN
            SOLUTION_TOKEN.containsMatchIn(code) -> SOLUTION_FUNCTION
            else -> UNRECOGNISED
        }

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
