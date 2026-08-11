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
    fun `a served page yields the saved code`() = runBlocking<Unit> {
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
    fun `the request carries the lesson and language of the problem`() = runBlocking<Unit> {
        var requested = ""
        val fetcher = ProblemPageCodeFetcher(pageBase = "https://example.test") { url ->
            requested = url
            PageResponse(200, page)
        }

        fetcher.fetch(120804, "java")

        requested shouldContain "/learn/courses/30/lessons/120804"
        requested shouldContain "language=java"
    }

    @Test
    fun `a login redirect is reported as unauthenticated rather than as a missing page`() = runBlocking<Unit> {
        val fetcher = fetcherReturning(PageResponse(302, "", location = "/users/sign_in"))

        fetcher.fetch(120804, "java") shouldBe CodeFetch.Unauthenticated
    }

    @Test
    fun `a rate-limit response is its own outcome so the caller can back off`() = runBlocking<Unit> {
        val fetcher = fetcherReturning(PageResponse(429, ""))

        fetcher.fetch(120804, "java") shouldBe CodeFetch.RateLimited
    }

    @Test
    fun `a page without a code input never yields an empty solution`() = runBlocking<Unit> {
        val fetcher = fetcherReturning(PageResponse(200, "<html><body>nothing here</body></html>"))

        fetcher.fetch(120804, "java").shouldBeInstanceOf<CodeFetch.Unavailable>()
    }

    @Test
    fun `an unexpected status is unavailable, not a crash`() = runBlocking<Unit> {
        val fetcher = fetcherReturning(PageResponse(500, "boom"))

        fetcher.fetch(120804, "java").shouldBeInstanceOf<CodeFetch.Unavailable>()
    }

    /**
     * Dev rules §7.2 — the cookie must not surface in any message, at any level.
     *
     * This used to plant a value in the fetcher's own `SessionCookie` and assert it did not
     * reach the failure reason. The fetcher no longer takes one (#180), so the guarantee is
     * now structural, and that is what is pinned: **no constructor parameter can carry a
     * credential.** A test that reads a value the class cannot hold would pass forever without
     * checking anything, which is the failure mode this repository keeps finding.
     */
    @Test
    fun `the fetcher cannot hold a credential at all`() {
        val carries = ProblemPageCodeFetcher::class.constructors
            .flatMap { it.parameters }
            .map { it.type.toString() }

        carries.none { it.contains("SessionCookie") } shouldBe true
    }

    @Test
    fun `a failure reason says only what went wrong`() = runBlocking<Unit> {
        val fetcher = ProblemPageCodeFetcher(pageBase = "https://example.test") {
            PageResponse(500, "boom")
        }

        val outcome = fetcher.fetch(120804, "java") as CodeFetch.Unavailable

        outcome.reason shouldContain "500"
        outcome.reason shouldNotContain "_session_production"
    }

    @Test
    fun `a transport failure is unavailable rather than a thrown exception`() = runBlocking<Unit> {
        val fetcher = ProblemPageCodeFetcher(pageBase = "https://example.test") {
            throw java.io.IOException("connection reset")
        }

        fetcher.fetch(120804, "java").shouldBeInstanceOf<CodeFetch.Unavailable>()
    }

    private fun fetcherReturning(response: PageResponse) =
        ProblemPageCodeFetcher(pageBase = "https://example.test") { response }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }
}
