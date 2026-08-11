package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.application.RecordQuery
import com.brokenfinger.tracker.application.UnresolvableProblemException
import com.brokenfinger.tracker.application.WatchCapacityExceededException
import com.brokenfinger.tracker.application.WatchCommand
import com.brokenfinger.tracker.application.WatchOutcome
import com.brokenfinger.tracker.application.WatchRequestHandler
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SessionState
import com.brokenfinger.tracker.domain.SubscriptionHealth
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import com.brokenfinger.tracker.support.fixtures.aWatchBody
import com.brokenfinger.tracker.support.fixtures.aWatchCommand
import com.brokenfinger.tracker.support.fixtures.aWatchStatus
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * The one Spring slice test the test-environment ADR allows — web controllers only
 * ([[decisions/2026-08-04-test-environment]]). It covers what plain unit tests cannot:
 * status-code mapping, the token header, and the on-the-wire error contract.
 */
@WebMvcTest(WatchController::class)
@Import(WatchControllerTest.Beans::class)
class WatchControllerTest {
    @TestConfiguration
    class Beans {
        @Bean
        fun watchRequestHandler(): WatchRequestHandler = mockk()

        @Bean
        fun watchToken(): WatchToken = WatchToken(GRANTED, "build/tmp/watch-token-should-not-be-created")

        /** Read-only, and mocked: this slice is about the wire contract, not about the log. */
        @Bean
        fun recordQuery(): RecordQuery = mockk()
    }

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var handler: WatchRequestHandler

    @Autowired
    private lateinit var records: RecordQuery

    @BeforeEach
    fun resetHandler() {
        clearMocks(handler)
        clearMocks(records)
        // The common case: nothing recorded for this problem yet, so the field is absent.
        every { records.lastRecordOf(any()) } returns null
    }

    @Test
    fun `starts watching and answers with the outcome`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(WatchOutcome.STARTED)

        val response = postWatch(aWatchBody())

        response.status shouldBe 200
        response.jsonBody().stringField("status") shouldBe "started"
        response.jsonBody().stringField("lessonId") shouldBe "120804"
    }

    /**
     * The field that stops `status` being a promise. Before #167 the body said `started` for
     * a socket the judge had refused, and the badge had nothing to go on.
     */
    @Test
    fun `the answer says whether the channel is actually being observed`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(health = SubscriptionHealth.REJECTED)

        val response = postWatch(aWatchBody())

        response.status shouldBe 200
        response.jsonBody().stringField("status") shouldBe "started"
        response.jsonBody().stringField("subscription") shouldBe "rejected"
    }

    /**
     * The field the socket cannot supply. An unauthenticated subscription is confirmed and
     * pinged normally and delivers nothing (protocol §15.3), so `subscription: live` beside
     * `session: expired` is a real and important combination — not a contradiction (#179).
     */
    @Test
    fun `a live subscription with a dead cookie says both`() {
        coEvery { handler.watch(any()) } returns
            aWatchStatus(health = SubscriptionHealth.LIVE, session = SessionState.EXPIRED)

        val body = postWatch(aWatchBody()).jsonBody()

        body.stringField("subscription") shouldBe "live"
        body.stringField("session") shouldBe "expired"
    }

    @Test
    fun `an unknown session is not reported as expired`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(session = SessionState.UNKNOWN)

        postWatch(aWatchBody()).jsonBody().stringField("session") shouldBe "unknown"
    }

    @Test
    fun `a live subscription says so`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(health = SubscriptionHealth.LIVE)

        postWatch(aWatchBody()).jsonBody().stringField("subscription") shouldBe "live"
    }

    @Test
    fun `hands the parsed command to the watcher`() {
        val captured = slot<WatchCommand>()
        coEvery { handler.watch(capture(captured)) } returns aWatchStatus(WatchOutcome.STARTED)

        postWatch(aWatchBody())

        captured.captured shouldBe aWatchCommand()
    }

    @Test
    fun `reports a repeat of an already watched channel as a refresh`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(WatchOutcome.REFRESHED)

        val response = postWatch(aWatchBody())

        response.status shouldBe 200
        response.jsonBody().stringField("status") shouldBe "refreshed"
    }

    /**
     * The problem page could not be read, so there is no channel to subscribe to (#114).
     * 502 rather than 400: the request was well formed and the failure is upstream, which an
     * expired session is the commonest cause of.
     */
    @Test
    fun `answers a gateway error when the lesson cannot be resolved to a channel`() {
        coEvery { handler.watch(any()) } throws UnresolvableProblemException(120804)

        val response = postWatch(aWatchBody())

        response.status shouldBe 502
        val body = response.jsonBody()
        body.stringField("error") shouldBe "PROBLEM_UNRESOLVED"
        body.stringField("message") shouldContain "session"
    }

    @Test
    fun `rejects a missing field, and never reaches the watcher`() {
        val response = postWatch(aWatchBody(omit = setOf("language")))

        response.status shouldBe 400
        response.jsonBody().stringField("field") shouldBe "language"
        coVerify(exactly = 0) { handler.watch(any()) }
    }

    @Test
    fun `rejects an unparseable identifier`() {
        val response = postWatch(aWatchBody(lessonId = "\"undefined\""))

        response.status shouldBe 400
        response.jsonBody().stringField("field") shouldBe "lessonId"
    }

    @Test
    fun `rejects a malformed body`() {
        val response = postWatch("{ not json")

        response.status shouldBe 400
        response.jsonBody().stringField("error") shouldBe "INVALID_REQUEST"
    }

    @Test
    fun `rejects an empty body`() {
        val response = postWatch("")

        response.status shouldBe 400
        response.jsonBody().stringField("error") shouldBe "INVALID_REQUEST"
    }

    @Test
    fun `refuses a request that carries no token`() {
        val response = postWatch(aWatchBody(), credential = null)

        response.status shouldBe 401
        response.jsonBody().stringField("error") shouldBe "UNAUTHORIZED"
        coVerify(exactly = 0) { handler.watch(any()) }
    }

    @Test
    fun `refuses a request that carries the wrong token`() {
        val response = postWatch(aWatchBody(), credential = REFUSED)

        response.status shouldBe 401
        coVerify(exactly = 0) { handler.watch(any()) }
    }

    @Test
    fun `checks the token before it looks at the body`() {
        val response = postWatch("{ not json", credential = null)

        response.status shouldBe 401
    }

    @Test
    fun `answers a saturated watcher with service unavailable rather than a silent no-op`() {
        coEvery { handler.watch(any()) } throws
            WatchCapacityExceededException("all 8 subscription slots are held by live gradings")

        val response = postWatch(aWatchBody())

        response.status shouldBe 503
        val body = response.jsonBody()
        body.stringField("error") shouldBe "WATCHER_SATURATED"
        body.stringField("message")!!.shouldContain("8")
    }

    @Test
    fun `never leaks a stack trace or the token into an error body`() {
        val response = postWatch(aWatchBody(), credential = REFUSED)

        val raw = response.contentAsString
        raw.shouldNotContain(GRANTED)
        raw.shouldNotContain(REFUSED)
        raw.shouldNotContain("Exception")
        response.jsonBody()["trace"].shouldBeNull()
    }

    /**
     * What the badge reads on every heartbeat (#156). Before this, a page announcing a pass and
     * a server that recorded nothing were indistinguishable from the toolbar.
     */
    @Test
    fun `the answer carries the newest grading recorded for that problem`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(WatchOutcome.REFRESHED)
        every { records.lastRecordOf(120804) } returns aSubmissionRecord(
            lessonId = 120804,
            verdict = Verdict.PASS,
            testcases = listOf(aTestcaseResult(), aTestcaseResult()),
        )

        val last = postWatch(aWatchBody()).jsonBody()["lastRecord"]!!.jsonObject

        last["verdict"]!!.jsonPrimitive.content shouldBe "PASS"
        last["outcome"]!!.jsonPrimitive.content shouldBe "JUDGED"
        last["passed"]!!.jsonPrimitive.int shouldBe 2
        last["action"]!!.jsonPrimitive.content shouldBe "submit"
    }

    /**
     * Absent, not null and not a placeholder. A grading the server could not classify is the
     * one case worth alarming on, and it is told apart from "nothing recorded here yet" only
     * by this key being present while `verdict` is not.
     */
    @Test
    fun `an unclassified grading is reported with an outcome and no verdict`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(WatchOutcome.REFRESHED)
        every { records.lastRecordOf(120804) } returns aSubmissionRecord(
            lessonId = 120804,
            verdict = null,
            outcome = Outcome.UNKNOWN,
            testcases = emptyList(),
        )

        val last = postWatch(aWatchBody()).jsonBody()["lastRecord"]!!.jsonObject

        last.shouldNotContainKey("verdict")
        last["outcome"]!!.jsonPrimitive.content shouldBe "UNKNOWN"
    }

    @Test
    fun `a problem with nothing recorded carries no record at all`() {
        coEvery { handler.watch(any()) } returns aWatchStatus(WatchOutcome.REFRESHED)

        postWatch(aWatchBody()).jsonBody().shouldNotContainKey("lastRecord")
    }

    private fun postWatch(body: String, credential: String? = GRANTED): MockHttpServletResponse = mvc.post("/watch") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        credential?.let { header(WatchController.TOKEN_HEADER, it) }
    }.andReturn().response

    private fun MockHttpServletResponse.jsonBody(): JsonObject = Json.parseToJsonElement(contentAsString) as JsonObject

    private fun JsonObject.stringField(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private companion object {
        /** Fixture values only — never a real credential. */
        const val GRANTED = "fixture-local-value"
        const val REFUSED = "a-different-value"
    }
}
