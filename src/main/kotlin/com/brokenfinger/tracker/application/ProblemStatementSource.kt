package com.brokenfinger.tracker.application

/**
 * Fetches a problem's statement on its own, for problems solved before the server kept one (#280).
 *
 * **Separate from the code fetch on purpose.** At capture time the statement rides on
 * [CodeFetcher]'s response for free — one page, both artifacts, no extra request. Here the code
 * is already attached and only the statement is missing, and `CodeFetcher` answers `Unavailable`
 * for a page with no saved-code input before it ever looks at a statement. Two readers of one
 * page is not duplication when one is a free ride and the other is a one-time repair.
 */
fun interface ProblemStatementSource {
    suspend fun statementOf(lessonId: Long, language: String): StatementFetch
}

/** What one attempt to fetch a statement produced. */
sealed interface StatementFetch {
    data class Fetched(val markdown: String) : StatementFetch

    /**
     * An expired session or a rate limit — conditions **every remaining problem shares**, so the
     * pass stops rather than spending the rest of its budget learning the same thing again. The
     * discipline `CodeAttachment.attachPending` already follows.
     */
    data object Blocked : StatementFetch

    /** This problem only. Nothing is written and the next boot tries it again. */
    data class Unavailable(val reason: String) : StatementFetch
}
