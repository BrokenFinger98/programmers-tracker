package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ProblemExample

/**
 * Where the measured example pairs of one problem are kept — the runner generator's whole
 * input (#37).
 *
 * Replace-only, by design: the judge's current examples are the truth, and a merge with
 * yesterday's file would keep examples the problem no longer has. An empty list is a no-op
 * rather than a deletion, because a submit announces no examples and must not blank what the
 * preceding run wrote.
 */
fun interface ExampleStore {
    fun replace(lessonId: Long, title: String?, examples: List<ProblemExample>)
}
