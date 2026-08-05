package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.CodeFetch
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Failure paths are the point here. A fetch failure must degrade the record to `codePending`
 * and never destroy it, because the verdict it belongs to can never be captured again
 * (protocol doc §11).
 */
class ProblemPageCodeFetcherTest {
    private val page = readFixture("lesson-page-saved-code.html")

    @Test
    fun `a served page yields the saved code`() = runBlocking {
        val fetcher = fetcherReturning(PageResponse(200, page))

        fetcher.fetch(120804, "java") shouldBe CodeFetch.Fetched(
            """
            class Solution {
                public int solution(int num1, int num2) {
                    return num1 * num2;
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `the request carries the lesson and language of the problem`() = runBlocking {
        var requested = ""
        val fetcher = ProblemPageCodeFetcher(aFakeCookie(), pageBase = "https://example.test") { url ->
            requested = url
            PageResponse(200, page)
        }

        fetcher.fetch(120804, "java")

        requested shouldContain "/learn/courses/30/lessons/120804"
        requested shouldContain "language=java"
    }

    @Test
    fun `a login redirect is reported as unauthenticated rather than as a missing page`() = runBlocking {
        val fetcher = fetcherReturning(PageResponse(302, "", location = "/users/sign_in"))

        fetcher.fetch(120804, "java") shouldBe CodeFetch.Unauthenticated
    }

    @Test
    fun `a rate-limit response is its own outcome so the caller can back off`() = runBlocking {
        val fetcher = fetcherReturning(PageResponse(429, ""))

        fetcher.fetch(120804, "java") shouldBe CodeFetch.RateLimited
    }

    @Test
    fun `a page without a code input never yields an empty solution`() = runBlocking {
        val fetcher = fetcherReturning(PageResponse(200, "<html><body>nothing here</body></html>"))

        fetcher.fetch(120804, "java").shouldBeInstanceOf<CodeFetch.Unavailable>()
    }

    @Test
    fun `an unexpected status is unavailable, not a crash`() = runBlocking {
        val fetcher = fetcherReturning(PageResponse(500, "boom"))

        fetcher.fetch(120804, "java").shouldBeInstanceOf<CodeFetch.Unavailable>()
    }

    /** Dev rules §7.2 — the cookie must not surface in any message, at any level. */
    @Test
    fun `the failure reason never contains the session value`() = runBlocking {
        val planted = "synthetic-value-for-this-test"
        val fetcher = ProblemPageCodeFetcher(aFakeCookie(planted), pageBase = "https://example.test") {
            PageResponse(500, "boom")
        }

        val outcome = fetcher.fetch(120804, "java") as CodeFetch.Unavailable

        outcome.reason shouldNotContain planted
    }

    @Test
    fun `a transport failure is unavailable rather than a thrown exception`() = runBlocking {
        val fetcher = ProblemPageCodeFetcher(aFakeCookie(), pageBase = "https://example.test") {
            throw java.io.IOException("connection reset")
        }

        fetcher.fetch(120804, "java").shouldBeInstanceOf<CodeFetch.Unavailable>()
    }

    private fun aFakeCookie(value: String = "fake-value-for-tests") = SessionCookie("_session_production=$value")

    private fun fetcherReturning(response: PageResponse) =
        ProblemPageCodeFetcher(aFakeCookie(), pageBase = "https://example.test") { response }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }
}
