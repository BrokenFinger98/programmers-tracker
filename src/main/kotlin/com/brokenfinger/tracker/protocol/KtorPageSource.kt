package com.brokenfinger.tracker.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * Ktor-backed [PageSource]. Redirects are not followed, because a redirect towards the
 * sign-in page is itself the signal that the session expired (design §4.3).
 *
 * The cookie arrives as a supplier rather than a value so one client can outlive one session:
 * the user pastes a fresh cookie into the session file when the old one expires (dev rules
 * §9.2), and a value captured at construction would keep sending the dead one until a restart.
 */
class KtorPageSource(private val cookieHeader: () -> String) :
    PageSource,
    AutoCloseable {
    private val client = HttpClient(CIO) { followRedirects = false }

    override suspend fun get(url: String): PageResponse {
        val response = client.get(url) {
            header(HttpHeaders.Cookie, cookieHeader())
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        return PageResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            location = response.headers[HttpHeaders.Location],
        )
    }

    override fun close() = client.close()

    private companion object {
        // Identifies us honestly rather than impersonating a browser; the requests we send
        // are at the same level a browser sends (README courtesy section).
        const val USER_AGENT = "programmers-tracker (personal learning record)"
    }
}
