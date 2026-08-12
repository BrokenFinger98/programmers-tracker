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

    /**
     * **The run path has its own time limit and its own sentence for it** — 10 seconds, against
     * the ~87 seconds measured for a submit (protocol doc §7.3, measured 2026-08-12 on lesson
     * 120802). It arrives as a `run` `error` frame, not as a testcase message, and it shares no
     * words with the submit path's sentence — the phrase [timeoutMessage] matches does not
     * appear in it at all.
     *
     * So it matched nothing, fell through to [errorVerdictOf] and was recorded as a
     * RUNTIME_ERROR — the learner's code was slow, and the record said it had crashed (#222).
     * Those two ask for opposite next moves.
     *
     * Kept as a second pattern rather than folded into [timeoutMessage] with an alternation.
     * They are two measurements of two different limits on two different paths, and a single
     * loosened regex would stop saying which of them was seen.
     */
    private val runTimeLimitMessage = Regex("""실행 시간이 [\d.]+초를 초과""")

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
     * RUNTIME_ERROR — the regex was javac's and nothing said so (#160). A language whose
     * compiler is not in this list lands as RUNTIME_ERROR, which is the honest fallback:
     * guessing at a format buys nothing when it is right and misclassifies when it is wrong.
     *
     * **Every one of the seven supported languages was broken on purpose and captured on
     * 2026-08-12 (#212), and the result was not what the list looked like.** Four languages
     * were already classified correctly *by accident*: clang and kotlinc print
     * `file:line:column: error:`, which the javac entry matches on its `:column: error:`
     * tail, and node prints `SyntaxError:`, which the python entry matches. An accident that
     * holds is still an accident — it survives only until a toolchain drops the column or
     * renames the exception, and nothing would have said which languages were resting on it.
     * So each is named below with its own capture, and the entries that were doing the work
     * unknowingly say so.
     *
     * The two genuine misses are fixed here: C# brackets its position (`Solution.cs(10,31):
     * error CS1002:`) so no colon-digit-colon appears, and Python's `IndentationError` and
     * `TabError` are SyntaxError subclasses that print their own names instead.
     */
    private val compilerDiagnostics = listOf(
        // javac — "/Solution.java:7: error: ';' expected", fixture java-compile-error.jsonl.
        // Also matches, on its ":column: error:" tail rather than by design:
        //   clang   "/solution0.cpp:8:15: error: expected ';' after expression"  (cpp, c)
        //   kotlinc "/Solution0.kt:3:15: error: syntax error: Expecting ')'."    (kotlin)
        // Fixtures cpp-, c- and kotlin-compile-error.jsonl pin all three, so a change here
        // fails loudly for the languages that were never mentioned.
        Regex(""":\d+: error:"""),
        // python3 — "SyntaxError: expected ':'", measured on lesson 120805.
        // Also matches node's "SyntaxError: missing ) after argument list", pinned by
        // javascript-compile-error.jsonl. JavaScript has no compile step of its own; the
        // parse failure is reported before any line runs, which is the same phase.
        Regex("""SyntaxError:"""),
        // python3 — the two SyntaxError subclasses that print their own name. Same parse
        // phase, so the same verdict. Fixtures python-indentation-error.jsonl, python-tab-error.jsonl
        Regex("""IndentationError:"""),
        Regex("""TabError:"""),
        // csharp — "/Solution0.cs(10,31): error CS1002: ; expected [/Solution.exe.csproj]".
        // The position is bracketed, so nothing above matches it and a C# compile failure was
        // filed as RUNTIME_ERROR. Fixture csharp-compile-error.jsonl
        Regex("""error CS\d+:"""),
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
        // Before the compiler shapes, because the run limit is not a failure to build or to run
        // — it is a solution that works and is too slow, and it reaches here with no testcase
        // of its own to carry the message (#222).
        if (runTimeLimitMessage.containsMatchIn(errorText)) return Verdict.TIMEOUT
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
