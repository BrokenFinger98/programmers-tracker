package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.domain.SessionState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The status is the whole signal, so these are one test per status class — including the ones
 * that must **not** be read as an expired cookie.
 *
 * The mapping comes from measurement, not from guessing what an API ought to do: four endpoints
 * were compared with and without the cookie on 2026-08-11, and this is the only one that
 * answered 200 authenticated and 401 not (protocol doc §14, #179).
 */
class SessionActivityProbeTest {
    @Test
    fun `200 means the session still authenticates`() = runBlocking<Unit> {
        probeOver(PageResponse(200, """{"activities":[]}""")).probe() shouldBe SessionState.ALIVE
    }

    @Test
    fun `401 means it does not`() = runBlocking<Unit> {
        probeOver(PageResponse(401, """{"code":"authenticate_user"}""")).probe() shouldBe SessionState.EXPIRED
    }

    /**
     * The direction that matters. Telling someone to replace a working credential is how the one
     * message that means "replace your credential" gets ignored.
     */
    @Test
    fun `a server error is unknown, never expired`() = runBlocking<Unit> {
        probeOver(PageResponse(500, "")).probe() shouldBe SessionState.UNKNOWN
    }

    /** §14 records this API family failing as 200-with-HTML rather than 429. */
    @Test
    fun `a throttle is unknown too`() = runBlocking<Unit> {
        probeOver(PageResponse(429, "")).probe() shouldBe SessionState.UNKNOWN
    }

    /**
     * The one the status alone gets wrong. §14: this API family throttles as **200 with an HTML
     * error page**, so trusting the status would read a rate limit as "your session is fine"
     * (#191). Whether this particular endpoint does that is unmeasured — the check is correct
     * either way, and triggering a rate limit to find out would be hammering Programmers to
     * prove a property we can simply stop claiming.
     */
    @Test
    fun `a 200 carrying an error page is not a live session`() = runBlocking<Unit> {
        val throttled = PageResponse(200, "<html><body>서비스 접속 오류</body></html>")

        probeOver(throttled).probe() shouldBe SessionState.UNKNOWN
    }

    @Test
    fun `an empty 200 is not a live session either`() = runBlocking<Unit> {
        probeOver(PageResponse(200, "   ")).probe() shouldBe SessionState.UNKNOWN
    }

    /**
     * The shape check must not swallow the answer that matters: a 401 whose body is the measured
     * JSON is still an expired session.
     */
    @Test
    fun `the shape check does not hide a real 401`() = runBlocking<Unit> {
        val measured = PageResponse(401, """{"code":"authenticate_user","message":"…"}""")

        probeOver(measured).probe() shouldBe SessionState.EXPIRED
    }

    @Test
    fun `a probe that cannot reach Programmers is unknown`() = runBlocking<Unit> {
        val unreachable = SessionActivityProbe(pages = { throw java.io.IOException("no route") })

        unreachable.probe() shouldBe SessionState.UNKNOWN
    }

    @Test
    fun `it asks the measured endpoint for the current year`() = runBlocking<Unit> {
        var asked = ""
        val probe = SessionActivityProbe(
            pages = { url ->
                asked = url
                PageResponse(200, "{}")
            },
            base = "https://example.test",
            year = { 2026 },
        )

        probe.probe()

        asked shouldBe "https://example.test/api/v1/main/open-challenge-activities?year=2026"
    }

    /**
     * The URL is built from a year, not from anything user-specific — a probe that carried an
     * identifier would be a second place for personal data to travel.
     */
    @Test
    fun `the request carries nothing about the user`() = runBlocking<Unit> {
        var asked = ""
        val probe = SessionActivityProbe(pages = { url ->
            asked = url
            PageResponse(200, "{}")
        })

        probe.probe()

        asked shouldContain "open-challenge-activities"
        asked.contains("lesson") shouldBe false
    }

    private fun probeOver(response: PageResponse) = SessionActivityProbe(pages = { response })
}
