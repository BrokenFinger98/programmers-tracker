package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.domain.Verdict

/**
 * Resolves the verdict of a grading from its testcases plus, when one is bound, the error
 * text of the immediately preceding run (design §3.3).
 *
 * Pure: input is an in-memory snapshot, output is the verdict, and nothing here knows about
 * files, sockets or the wire format (development-rules §3).
 *
 * Returns null when the failure matches nothing measured. The memory-limit message has
 * never been triggered, so its string is unknown (protocol doc §14); coercing it into a
 * neighbouring verdict is precisely the silent-wrong-data failure the constitution ranks
 * worst. The caller records that as an UNKNOWN outcome, not as a guess.
 */
object VerdictResolver {
    // Measured failure messages (protocol doc §7). The submit path carries neither exitCode
    // nor stderr, so the message string is the only clue there is.
    private val timeoutMessage = Regex("시간 초과")
    private val runtimeFailureMessage = Regex("런타임 에러")

    // A wrong answer is the only failure that still reports timing, as in "(0.01ms, 75.3MB)".
    private val measuredMessage = Regex("""\d+(\.\d+)?ms""")

    /**
     * Compile failures, **by the shape each toolchain actually prints**. A submit response
     * reports compile and runtime errors identically; only the run path separates them
     * (protocol doc §7). Matching stays tolerant of HTML escaping, which the run path applies
     * to the whole message.
     *
     * Measured only, and that is the rule rather than an accident of effort. The javac shape
     * was here alone until 2026-08-11, when a Python `def` missing its colon was filed as
     * RUNTIME_ERROR — the regex was javac's and nothing said so (#162). A language whose
     * compiler is not in this list lands as RUNTIME_ERROR, which is the honest fallback:
     * guessing at a format buys nothing when it is right and misclassifies when it is wrong.
     *
     * Not here on purpose: `IndentationError` and `TabError`, which are SyntaxError subclasses
     * that print their own names. Neither has been captured. One line each when they are.
     */
    private val compilerDiagnostics = listOf(
        // javac — "/Solution.java:5: error: ';' expected"
        Regex(""":\d+: error:"""),
        // python3 — a traceback ending "SyntaxError: expected ':'", measured on lesson 120805
        Regex("""SyntaxError:"""),
    )

    fun resolve(testcases: List<TestcaseResult>, boundErrorText: String?): Verdict? {
        if (testcases.isEmpty()) return nothingRanVerdict(boundErrorText)
        val failed = testcases.sortedBy { it.id }.firstOrNull { it.hasFailed() } ?: return Verdict.PASS
        return verdictOf(failed.msg, boundErrorText)
    }

    /**
     * No testcase reported, which is what a compile failure looks like: the run path emits
     * `start` and then error frames, and nothing ever ran (protocol doc §7). Until #151 this
     * returned null and a compile error was filed as UNKNOWN — one of the five verdicts the
     * README advertises, lost because the check that would have caught it sat behind an early
     * return. Measured 2026-08-11 on lesson 181946.
     *
     * **A recognised unknown stays unknown.** A cached result is also a terminal error frame
     * with no testcases, and reading its text as a failure would file a grading the learner
     * never failed as a RUNTIME_ERROR — the silent-wrong-data outcome the constitution ranks
     * worst.
     */
    private fun nothingRanVerdict(errorText: String?): Verdict? {
        if (errorText == null) return null
        if (UnknownReason.matching(errorText) != null) return null
        return errorVerdictOf(errorText)
    }

    private fun verdictOf(msg: String?, boundErrorText: String?): Verdict? {
        if (msg == null) return null
        if (timeoutMessage.containsMatchIn(msg)) return Verdict.TIMEOUT
        if (runtimeFailureMessage.containsMatchIn(msg)) return errorVerdictOf(boundErrorText)
        if (measuredMessage.containsMatchIn(msg)) return Verdict.WRONG
        return null
    }

    private fun errorVerdictOf(boundErrorText: String?): Verdict {
        if (boundErrorText == null) return Verdict.RUNTIME_ERROR
        if (compilerDiagnostics.any { it.containsMatchIn(boundErrorText) }) return Verdict.COMPILE_ERROR
        return Verdict.RUNTIME_ERROR
    }
}
