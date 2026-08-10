package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.adapter.web.WatchToken
import com.brokenfinger.tracker.application.RecordQuery
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aLegacyBody
import com.brokenfinger.tracker.support.fixtures.aModernBody
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aToolCallParams
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import com.brokenfinger.tracker.support.fixtures.anInitializeParams
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * The contract test design §10 asks for, driven over the real endpoint.
 *
 * It asserts on **wire responses**, not on Kotlin objects, because everything this slice
 * is worth depends on the bytes an MCP client actually receives: an object that is correct
 * in Kotlin and wrong in JSON is a server no client can talk to.
 */
@WebMvcTest(McpController::class)
@Import(McpControllerTest.Beans::class)
class McpControllerTest {
    @TestConfiguration
    class Beans {
        @Bean
        fun mcpDispatcher(): McpDispatcher =
            McpDispatcher(McpToolInvoker(RecordQuery(scratchStore(), anEmptyCatalog(), Clock.systemUTC())))

        @Bean
        fun watchToken(): WatchToken = WatchToken(GRANTED, "build/tmp/mcp-token-should-not-be-created")

        @Bean
        fun mcpAllowedOrigins(): McpAllowedOrigins = McpAllowedOrigins("")

        /**
         * A record repository under `build/`, never the user's and never `~/ps-records`.
         * Rebuilt every run, so what these assertions see is what this test wrote and not
         * what a previous run happened to leave behind.
         */
        private fun scratchStore(): JsonlRecordStore {
            val log = RecordLayout(Path.of("build/tmp/mcp-controller-test")).submissionLog()
            Files.createDirectories(log.parent)
            Files.deleteIfExists(log)
            return JsonlRecordStore(log).also { it.append(SubmissionRecordJson.encode(aSubmissionRecord())) }
        }
    }

    @Autowired
    private lateinit var mvc: MockMvc

    // ------------------------------------------------------------------ the handshake era

    /** initialize → tools/list → tools/call, the sequence every shipping client performs. */
    @Test
    fun `serves a handshake client through the whole opening sequence`() {
        val initialize = json(post(aLegacyBody("initialize", anInitializeParams("2025-11-25"), id = 1)))
        initialize["jsonrpc"]!!.jsonPrimitive.content shouldBe "2.0"
        initialize["id"]!!.jsonPrimitive.int shouldBe 1
        val opened = initialize["result"]!!.jsonObject
        opened["protocolVersion"]!!.jsonPrimitive.content shouldBe "2025-11-25"
        opened["capabilities"]!!.jsonObject["tools"].shouldNotBeNull()
        opened["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "programmers-tracker"

        post(aLegacyBody("notifications/initialized", id = null)).status shouldBe 202

        val listed = json(post(aLegacyBody("tools/list", id = 2)))["result"]!!.jsonObject
        listed["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .shouldContainExactly(McpToolCatalog.NAMES)

        val params = aToolCallParams("stats", buildJsonObject { put("groupBy", "verdict") })
        val called = json(post(aLegacyBody("tools/call", params, id = 3)))["result"]!!.jsonObject
        called["isError"]!!.jsonPrimitive.booleanOrNull!!.shouldBeFalse()
        called["structuredContent"]!!.jsonObject["total"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `answers a handshake tool call with both structured and text content`() {
        val params = aToolCallParams("submissions")

        val result = json(post(aLegacyBody("tools/call", params)))["result"]!!.jsonObject

        result["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content shouldBe "text"
        result["structuredContent"]!!.jsonObject["count"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `answers a notification with 202 and an empty body`() {
        val response = post(aLegacyBody("notifications/cancelled", id = null))

        response.status shouldBe 202
        response.contentAsString.shouldBeEmpty()
    }

    // --------------------------------------------------------------------- the modern era

    /** server/discover → tools/list → tools/call, the sequence the current revision defines. */
    @Test
    fun `serves a modern client through discovery and a tool call`() {
        val discovered = json(postModern("server/discover", id = 1))["result"]!!.jsonObject
        discovered["resultType"]!!.jsonPrimitive.content shouldBe "complete"
        discovered["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content }
            .shouldContainExactly(McpProtocol.MODERN)

        val listed = json(postModern("tools/list", id = 2))["result"]!!.jsonObject
        listed["tools"]!!.jsonArray.size shouldBe McpToolCatalog.NAMES.size
        listed["cacheScope"]!!.jsonPrimitive.content shouldBe "private"

        val params = aToolCallParams("get_problem", buildJsonObject { put("lessonId", 120804) })
        val called = json(postModern("tools/call", params, toolName = "get_problem", id = 3))["result"]!!.jsonObject
        called["structuredContent"]!!.jsonObject["submissionCount"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `refuses a modern request whose mirrored header disagrees with its body`() {
        val response = mvc.post(McpController.PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = aModernBody("tools/list")
            header(McpController.TOKEN_HEADER, GRANTED)
            header("MCP-Protocol-Version", McpProtocol.MODERN)
            header("Mcp-Method", "tools/call")
        }.andReturn().response

        response.status shouldBe 400
        errorCode(response) shouldBe McpErrors.HEADER_MISMATCH
    }

    @Test
    fun `refuses a revision it does not implement and says which it does`() {
        val response = mvc.post(McpController.PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = aModernBody("tools/list", version = "1900-01-01")
            header(McpController.TOKEN_HEADER, GRANTED)
            header("MCP-Protocol-Version", "1900-01-01")
            header("Mcp-Method", "tools/list")
        }.andReturn().response

        response.status shouldBe 400
        errorCode(response) shouldBe McpErrors.UNSUPPORTED_PROTOCOL_VERSION
        response.contentAsString.shouldContain(McpProtocol.MODERN)
    }

    @Test
    fun `answers an unknown modern method with 404 carrying the JSON-RPC code`() {
        val response = postModern("warmup/plan")

        response.status shouldBe 404
        errorCode(response) shouldBe McpErrors.METHOD_NOT_FOUND
    }

    // ------------------------------------------------------------------------ the guards

    @Test
    fun `refuses a request that carries no token`() {
        val response = post(aLegacyBody("tools/list"), credential = null)

        response.status shouldBe 401
        errorCode(response) shouldBe McpErrors.UNAUTHORIZED
    }

    @Test
    fun `refuses a request that carries the wrong token`() {
        post(aLegacyBody("tools/list"), credential = REFUSED).status shouldBe 401
    }

    /** The credential is checked before the body is even parsed. */
    @Test
    fun `checks the token before it looks at the body`() {
        post("{ not json", credential = null).status shouldBe 401
    }

    /**
     * The DNS-rebinding defence the specification makes a MUST: a page the user merely
     * visited must not be able to read a solving history by being rebound onto loopback.
     */
    @Test
    fun `refuses a request that carries a browser origin`() {
        val response = mvc.post(McpController.PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = aLegacyBody("tools/list")
            header(McpController.TOKEN_HEADER, GRANTED)
            header("Origin", "https://evil.example")
        }.andReturn().response

        response.status shouldBe 403
        errorCode(response) shouldBe McpErrors.FORBIDDEN_ORIGIN
    }

    @Test
    fun `refuses a malformed JSON-RPC envelope`() {
        val response = post("{ not json")

        response.status shouldBe 400
        errorCode(response) shouldBe McpErrors.PARSE
    }

    @Test
    fun `refuses an empty body`() {
        post("").status shouldBe 400
    }

    @Test
    fun `refuses a body with no method`() {
        errorCode(post("""{"jsonrpc":"2.0","id":1}""")) shouldBe McpErrors.INVALID_REQUEST
    }

    /** The modern revision removed the standalone SSE stream and protocol-level sessions. */
    @Test
    fun `offers no stream to open and no session to delete`() {
        mvc.get(McpController.PATH).andReturn().response.status shouldBe 405
        mvc.delete(McpController.PATH).andReturn().response.status shouldBe 405
    }

    @Test
    fun `never leaks the token or a stack trace into any answer`() {
        listOf(
            post(aLegacyBody("tools/list"), credential = REFUSED),
            post("{ not json"),
            post(aLegacyBody("warmup/plan")),
        ).forEach { response ->
            response.contentAsString.shouldNotContain(GRANTED)
            response.contentAsString.shouldNotContain(REFUSED)
            response.contentAsString.shouldNotContain("Exception")
            response.contentAsString.shouldNotContain("at com.brokenfinger")
        }
    }

    @Test
    fun `answers as JSON`() {
        post(aLegacyBody("tools/list")).contentType.shouldContain(MediaType.APPLICATION_JSON_VALUE)
    }

    private fun post(body: String, credential: String? = GRANTED): MockHttpServletResponse =
        mvc.post(McpController.PATH) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            credential?.let { header(McpController.TOKEN_HEADER, it) }
        }.andReturn().response

    private fun postModern(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        toolName: String? = null,
        id: Int = 1,
    ): MockHttpServletResponse = mvc.post(McpController.PATH) {
        contentType = MediaType.APPLICATION_JSON
        content = aModernBody(method, params, id = id)
        header(McpController.TOKEN_HEADER, GRANTED)
        header("MCP-Protocol-Version", McpProtocol.MODERN)
        header("Mcp-Method", method)
        toolName?.let { header("Mcp-Name", it) }
    }.andReturn().response

    private fun json(response: MockHttpServletResponse): JsonObject =
        Json.parseToJsonElement(response.contentAsString).jsonObject

    private fun errorCode(response: MockHttpServletResponse): Int =
        json(response)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    private companion object {
        /** Fixture values only — never a real credential. */
        const val GRANTED = "fixture-local-value"
        const val REFUSED = "a-different-value"
    }
}
