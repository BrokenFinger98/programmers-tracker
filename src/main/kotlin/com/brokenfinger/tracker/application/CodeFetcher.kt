package com.brokenfinger.tracker.application

/**
 * Outcome of retrieving the user's saved code. Modelled as a closed set rather than an
 * exception because every branch has a different consequence for the record, and none of
 * them may cost us the record itself (design §4.4, ADR 2026-08-05-capture-pipeline-stages).
 */
sealed interface CodeFetch {
    /**
     * The saved code, and the problem statement that was on the same page (#275).
     *
     * They travel together because they arrive together: this fetch already downloads the
     * problem page, so reading the statement out of the same response costs Programmers
     * nothing — which is what development-rules §9.3 asks of every request we make.
     *
     * `statement` is null when the page carried none. Absent, never a placeholder: a file
     * saying nothing is worse than no file, because only one of the two can be filled in later.
     */
    data class Fetched(val code: String, val statement: String? = null) : CodeFetch

    /** The session is no longer valid — feeds the one auth state, same as a rejected subscribe. */
    data object Unauthenticated : CodeFetch

    /** Programmers' exact rate-limit rules are unverified (protocol doc §14); back off. */
    data object RateLimited : CodeFetch

    /** Reached the page but found no saved code. The record keeps `codePending`. */
    data class Unavailable(val reason: String) : CodeFetch
}

/** Retrieves the last code Programmers has saved for a problem in one language. */
fun interface CodeFetcher {
    suspend fun fetch(lessonId: Long, language: String): CodeFetch
}
