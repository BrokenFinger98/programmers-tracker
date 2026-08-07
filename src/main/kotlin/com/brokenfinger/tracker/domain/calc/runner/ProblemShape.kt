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
        private val MAIN = Regex("""public\s+static\s+void\s+main\s*\(""")
        private val SOLUTION = Regex("""\bsolution\s*\(""")

        fun of(code: String): ProblemShape = when {
            MAIN.containsMatchIn(code) -> STDIN_MAIN
            SOLUTION.containsMatchIn(code) -> SOLUTION_FUNCTION
            else -> UNRECOGNISED
        }
    }
}
