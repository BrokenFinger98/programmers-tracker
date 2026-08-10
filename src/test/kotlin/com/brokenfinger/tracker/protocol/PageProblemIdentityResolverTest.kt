package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.ResolvedProblem
import com.brokenfinger.tracker.domain.ChallengeableId
import com.brokenfinger.tracker.domain.LessonId
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Resolving a lesson to its channel, so `/watch` needs only what the server cannot know
 * (#114).
 *
 * The cache is the load-bearing part, not an optimisation: the sensor heartbeats every 30
 * seconds per open tab, and a fetch on each of those would be one request per problem per
 * half-minute against Programmers — the opposite of development-rules §9.3.
 */
class PageProblemIdentityResolverTest {
    @Test
    fun `reads the channel identifiers off the problem page`() {
        val resolver = resolverOver(page(FixtureLoader.text("lesson-page-identifiers.html")))

        val resolved = runBlocking { resolver.resolve(LessonId(120803), "java") }

        resolved shouldBe ResolvedProblem(ChallengeableId(14642), ProblemKind.ALGORITHM)
    }

    /** Both values are fixed per problem (protocol §3), so one fetch answers every heartbeat. */
    @Test
    fun `fetches once per lesson, however often it is asked`() {
        val source = CountingPageSource(page(FixtureLoader.text("lesson-page-identifiers.html")))
        val resolver = PageProblemIdentityResolver(pages = source)

        runBlocking {
            repeat(5) { resolver.resolve(LessonId(120803), "java") }
            // A language switch asks again, and must still not refetch — §3 measured the
            // identifiers as language-independent.
            resolver.resolve(LessonId(120803), "python3")
        }

        source.calls shouldBe 1
    }

    /**
     * A failure is not cached. The commonest cause is an expired session, and remembering
     * the "no" would keep refusing long after the user pasted a fresh cookie.
     */
    @Test
    fun `a failure is retried rather than remembered`() {
        val source = CountingPageSource(page("<html>로그인이 필요합니다</html>"))
        val resolver = PageProblemIdentityResolver(pages = source)

        runBlocking {
            resolver.resolve(LessonId(120803), "java").shouldBeNull()
            resolver.resolve(LessonId(120803), "java").shouldBeNull()
        }

        source.calls shouldBe 2
    }

    @Test
    fun `a non-OK page resolves to nothing`() {
        val resolver = resolverOver(PageResponse(status = 500, body = "", location = null))

        runBlocking { resolver.resolve(LessonId(120803), "java") }.shouldBeNull()
    }

    /** A fetch that throws is a network blip, not a crash of the request that triggered it. */
    @Test
    fun `a throwing page source resolves to nothing rather than propagating`() {
        val resolver = PageProblemIdentityResolver(pages = { error("connection reset") })

        runBlocking { resolver.resolve(LessonId(120803), "java") }.shouldBeNull()
    }

    private fun resolverOver(response: PageResponse) = PageProblemIdentityResolver(pages = { response })

    private fun page(body: String) = PageResponse(status = 200, body = body, location = null)

    private class CountingPageSource(private val response: PageResponse) : PageSource {
        var calls = 0

        override suspend fun get(url: String): PageResponse {
            calls += 1
            return response
        }
    }
}
