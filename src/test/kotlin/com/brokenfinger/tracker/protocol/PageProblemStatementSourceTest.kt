package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.StatementFetch
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The backfill's own fetch (#280). It exists because the live path reads the statement off the
 * code fetch, which gives up on a page with no saved-code input **before** looking at a statement
 * — right when code is the point, wrong when the code is already attached and only the statement
 * is missing.
 *
 * What matters here is which failures stop the whole pass and which stop one problem, because
 * that is the difference between one wasted request and three hundred.
 */
class PageProblemStatementSourceTest {
    private val page = FixtureLoader.text("lesson-page-statement.html")

    @Test
    fun `a served page yields the statement as markdown`() = runBlocking<Unit> {
        val fetched = sourceReturning(PageResponse(200, page))
            .statementOf(120804, "java")
            .shouldBeInstanceOf<StatementFetch.Fetched>()

        fetched.markdown shouldContain "##### 제한사항"
    }

    @Test
    fun `the request names the lesson and the language it was recorded in`() = runBlocking<Unit> {
        var asked = ""
        PageProblemStatementSource(pageBase = "https://example.test") { url ->
            asked = url
            PageResponse(200, page)
        }.statementOf(120804, "kotlin")

        asked shouldBe "https://example.test/learn/courses/30/lessons/120804?language=kotlin"
    }

    /**
     * Blocking, not merely failing: every remaining problem shares this session, so asking them
     * one at a time only teaches us the same thing three hundred times.
     */
    @Test
    fun `a redirect to sign-in blocks the whole pass`() = runBlocking<Unit> {
        sourceReturning(PageResponse(302, "", location = "/users/sign_in"))
            .statementOf(120804, "java") shouldBe StatementFetch.Blocked
    }

    @Test
    fun `a rate limit blocks the whole pass too`() = runBlocking<Unit> {
        sourceReturning(PageResponse(429, "")).statementOf(120804, "java") shouldBe StatementFetch.Blocked
    }

    /** One problem's page being odd says nothing about the next one's. */
    @Test
    fun `a page with no statement region fails only this problem`() = runBlocking<Unit> {
        sourceReturning(PageResponse(200, "<html><body>nothing here</body></html>"))
            .statementOf(120804, "java")
            .shouldBeInstanceOf<StatementFetch.Unavailable>()
    }

    @Test
    fun `an unexpected status fails only this problem`() = runBlocking<Unit> {
        sourceReturning(PageResponse(500, "boom"))
            .statementOf(120804, "java")
            .shouldBeInstanceOf<StatementFetch.Unavailable>()
    }

    /** This runs at boot. A thrown error here must never be able to stop a server from starting. */
    @Test
    fun `a page source that throws is an outcome, not an exception`() = runBlocking<Unit> {
        PageProblemStatementSource(pageBase = "https://example.test") { error("socket died") }
            .statementOf(120804, "java")
            .shouldBeInstanceOf<StatementFetch.Unavailable>()
    }

    private fun sourceReturning(response: PageResponse) =
        PageProblemStatementSource(pageBase = "https://example.test") { response }
}
