package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.ProblemStatementSource
import com.brokenfinger.tracker.application.StatementFetch
import com.brokenfinger.tracker.protocol.parse.ProblemStatementPage

/**
 * The statement, read off the problem page the same way everything else here reads it.
 *
 * Every failure is an outcome rather than an exception: this repairs history at boot and must
 * never be able to stop a server from starting.
 */
class PageProblemStatementSource(private val pageBase: String = DEFAULT_BASE, private val pages: PageSource) :
    ProblemStatementSource {
    override suspend fun statementOf(lessonId: Long, language: String): StatementFetch =
        runCatching { outcomeOf(pages.get(urlOf(lessonId, language))) }
            .getOrElse { StatementFetch.Unavailable("page fetch failed: ${it.javaClass.simpleName}") }

    private fun outcomeOf(response: PageResponse): StatementFetch {
        if (response.status == RATE_LIMITED) return StatementFetch.Blocked
        // A redirect to sign-in is an expired session, which every remaining problem shares.
        if (isLoginRedirect(response)) return StatementFetch.Blocked
        if (response.status != OK) return StatementFetch.Unavailable("unexpected status ${response.status}")
        val markdown = ProblemStatementPage.statementOf(response.body)
            ?: return StatementFetch.Unavailable("page carried no statement region")
        return StatementFetch.Fetched(markdown)
    }

    private fun isLoginRedirect(response: PageResponse): Boolean {
        if (response.status !in REDIRECTS) return false
        return response.location?.contains(SIGN_IN) == true
    }

    // The cookie rides on the PageSource; this class never holds one, so it cannot put one in a
    // URL or a message even by accident (#180).
    private fun urlOf(lessonId: Long, language: String): String =
        "$pageBase/learn/courses/30/lessons/$lessonId?language=$language"

    private companion object {
        const val DEFAULT_BASE = "https://school.programmers.co.kr"
        const val OK = 200
        const val RATE_LIMITED = 429
        const val SIGN_IN = "sign_in"
        val REDIRECTS = 300..399
    }
}
