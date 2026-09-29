package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.application.SessionHealth
import com.brokenfinger.tracker.domain.SessionState
import com.brokenfinger.tracker.protocol.ManualFileSessionProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The wire contract of `POST /session` (#332): the token, the error shape, and above all that
 * the value handed over never comes back in the answer.
 */
@WebMvcTest(SessionController::class)
@Import(SessionControllerTest.Beans::class)
class SessionControllerTest {
    @TestConfiguration
    class Beans {
        val probes = AtomicInteger()

        @Bean
        fun watchToken(): WatchToken = WatchToken(GRANTED, "build/tmp/session-token-should-not-be-created")

        @Bean
        fun sessionProvider(): ManualFileSessionProvider =
            ManualFileSessionProvider(Files.createTempDirectory("session-controller").resolve("session"))

        @Bean
        fun sessionHealth(): SessionHealth = SessionHealth({
            probes.incrementAndGet()
            SessionState.ALIVE
        }, Clock.systemUTC())
    }

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var provider: ManualFileSessionProvider

    @Autowired
    private lateinit var beans: Beans

    @BeforeEach
    fun freshFile() {
        Files.deleteIfExists(provider.path)
    }

    @Test
    fun `a hand-off is accepted, written, probed at once, and never echoed`() {
        val response = postSession("""{"cookie":"$FAKE_VALUE"}""")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"accepted\":true"
        response.contentAsString shouldContain "\"changed\":true"
        response.contentAsString shouldContain "\"persisted\":true"
        response.contentAsString shouldContain "\"session\":\"alive\""
        response.contentAsString shouldNotContain FAKE_VALUE
        Files.readString(provider.path) shouldBe FAKE_VALUE
    }

    @Test
    fun `the same value again is a no-op`() {
        postSession("""{"cookie":"$FAKE_VALUE"}""")

        val response = postSession("""{"cookie":"$FAKE_VALUE"}""")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"changed\":false"
    }

    @Test
    fun `no token is refused, and nothing is written`() {
        val response = mvc.post(SessionController.PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"cookie":"$FAKE_VALUE"}"""
        }.andReturn().response

        response.status shouldBe 401
        response.contentAsString shouldContain "UNAUTHORIZED"
        Files.exists(provider.path) shouldBe false
    }

    @Test
    fun `a blank value is refused naming the field`() {
        val response = postSession("""{"cookie":"   "}""")

        response.status shouldBe 400
        response.contentAsString shouldContain "INVALID_REQUEST"
        response.contentAsString shouldContain "\"field\":\"cookie\""
    }

    @Test
    fun `a value with a line break is refused, and the answer does not repeat it`() {
        val response = postSession("""{"cookie":"abc\ndef"}""")

        response.status shouldBe 400
        response.contentAsString shouldNotContain "abc"
    }

    @Test
    fun `a body that is not an object is refused`() {
        postSession("[1,2]").status shouldBe 400
        postSession("not json").status shouldBe 400
    }

    /** JsonNull is a JsonPrimitive whose content reads "null" — the first cut wrote that word into the file. */
    @Test
    fun `a value that is not a string is refused, and the file is untouched`() {
        for (body in listOf("""{"cookie":null}""", """{"cookie":99999999}""", """{"cookie":false}""")) {
            val response = postSession(body)
            response.status shouldBe 400
            response.contentAsString shouldContain "must be a string"
        }
        Files.exists(provider.path) shouldBe false
    }

    /** Cookie-octets only: nothing that could smuggle a second cookie into the outbound header. */
    @Test
    fun `a value that is not cookie-shaped is refused`() {
        for (value in listOf("good; admin=true", "with space", "quo\"ted", "back\\\\slash", "com,ma")) {
            postSession("""{"cookie":"$value"}""").status shouldBe 400
        }
        // Base64 padding is fine, and so is the named form.
        postSession("""{"cookie":"abc=="}""").status shouldBe 200
        postSession("""{"cookie":"_session_production=xyz"}""").status shouldBe 200
    }

    /** A page rebound onto loopback carries its origin; the extension carries its own. */
    @Test
    fun `a web origin is refused, an extension origin is admitted`() {
        val fromPage = mvc.post(SessionController.PATH) {
            header(WatchController.TOKEN_HEADER, GRANTED)
            header("Origin", "https://evil.example")
            contentType = MediaType.APPLICATION_JSON
            content = """{"cookie":"$FAKE_VALUE"}"""
        }.andReturn().response
        fromPage.status shouldBe 403
        fromPage.contentAsString shouldContain "FORBIDDEN_ORIGIN"
        Files.exists(provider.path) shouldBe false

        val fromExtension = mvc.post(SessionController.PATH) {
            header(WatchController.TOKEN_HEADER, GRANTED)
            header("Origin", "chrome-extension://abcdefghijklmnop")
            contentType = MediaType.APPLICATION_JSON
            content = """{"cookie":"$FAKE_VALUE"}"""
        }.andReturn().response
        fromExtension.status shouldBe 200
    }

    private fun postSession(body: String): MockHttpServletResponse = mvc.post(SessionController.PATH) {
        header(WatchController.TOKEN_HEADER, GRANTED)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }.andReturn().response

    private companion object {
        const val GRANTED = "granted-token-for-tests"

        // Synthetic — never a real credential (dev rules §7.3).
        const val FAKE_VALUE = "fake-session-value-for-tests"
    }
}
