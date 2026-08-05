package com.brokenfinger.tracker.application

/**
 * Outcome of retrieving the user's saved code. Modelled as a closed set rather than an
 * exception because every branch has a different consequence for the record, and none of
 * them may cost us the record itself (design §4.4, ADR 2026-08-05-capture-pipeline-stages).
 */
sealed interface CodeFetch {
    data class Fetched(val code: String) : CodeFetch

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
