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

    // The javac diagnostic shape, as in "/Solution.java:5: error: ';' expected". A submit
    // response reports compile and runtime errors identically; only the run path separates
    // them (protocol doc §7). Matching stays tolerant of HTML escaping, which the run path
    // applies to the whole message.
    private val compilerDiagnostic = Regex(""":\d+: error:""")

    fun resolve(testcases: List<TestcaseResult>, boundErrorText: String?): Verdict? {
        if (testcases.isEmpty()) return null
        val failed = testcases.sortedBy { it.id }.firstOrNull { it.hasFailed() } ?: return Verdict.PASS
        return verdictOf(failed.msg, boundErrorText)
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
        if (compilerDiagnostic.containsMatchIn(boundErrorText)) return Verdict.COMPILE_ERROR
        return Verdict.RUNTIME_ERROR
    }
}
