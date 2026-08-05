package com.brokenfinger.tracker.domain

/**
 * What the judge concluded about a grading. All five kinds are measured
 * (protocol doc §7).
 *
 * There is deliberately no sixth constant for "we could not tell": the absence of a
 * verdict is expressed as `null` and carried by [Outcome]. A failure message we have
 * never measured must never be coerced into a neighbouring verdict.
 */
enum class Verdict {
    PASS,
    WRONG,
    TIMEOUT,
    RUNTIME_ERROR,
    COMPILE_ERROR,
}
