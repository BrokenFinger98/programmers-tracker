package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.CodeFetch
import com.brokenfinger.tracker.application.CodeFetcher
import com.brokenfinger.tracker.protocol.parse.SavedCodePage
import org.slf4j.LoggerFactory

/**
 * Reads the user's saved code from the problem page — the only source, since broadcasts
 * carry none (protocol doc §10).
 *
 * Every failure is an outcome rather than an exception: this runs after the verdict has
 * already been recorded, and a thrown error here must not be able to unwind anything that
 * matters (ADR 2026-08-05-capture-pipeline-stages).
 */
class ProblemPageCodeFetcher(private val pageBase: String = DEFAULT_BASE, private val pages: PageSource) :
    CodeFetcher {
    override suspend fun fetch(lessonId: Long, language: String): CodeFetch =
        runCatching { outcomeOf(pages.get(urlOf(lessonId, language))) }
            .getOrElse { CodeFetch.Unavailable("page fetch failed: ${it.javaClass.simpleName}") }

    private fun outcomeOf(response: PageResponse): CodeFetch {
        if (response.status == RATE_LIMITED) return CodeFetch.RateLimited
        if (isLoginRedirect(response)) return unauthenticated()
        if (response.status != OK) return CodeFetch.Unavailable("unexpected status ${response.status}")
        return savedCode(response.body)
    }

    private fun savedCode(body: String): CodeFetch {
        val code = SavedCodePage.savedCodeOf(body)
            ?: return CodeFetch.Unavailable("page carried no data-type=\"code\" input")
        return CodeFetch.Fetched(code)
    }

    // A 302 towards the sign-in page is how an expired session shows up here; it feeds the
    // same auth state as a rejected subscription rather than looking like a missing page.
    private fun isLoginRedirect(response: PageResponse): Boolean {
        if (response.status !in REDIRECTS) return false
        return response.location?.contains(SIGN_IN) == true
    }

    private fun unauthenticated(): CodeFetch {
        logger.warn("Problem page redirected to sign-in — the session is no longer valid")
        return CodeFetch.Unauthenticated
    }

    // The cookie is carried by the PageSource. This class never holds one, which is why it
    // cannot interpolate one into a URL or a message — structural rather than remembered
    // (#180; flagged as a MINOR by the 2026-08-07 security review).
    private fun urlOf(lessonId: Long, language: String): String =
        "$pageBase/learn/courses/30/lessons/$lessonId?language=$language"

    companion object {
        private val logger = LoggerFactory.getLogger(ProblemPageCodeFetcher::class.java)
        private const val DEFAULT_BASE = "https://school.programmers.co.kr"
        private const val OK = 200
        private const val RATE_LIMITED = 429
        private const val SIGN_IN = "sign_in"
        private val REDIRECTS = 300..399
    }
}
