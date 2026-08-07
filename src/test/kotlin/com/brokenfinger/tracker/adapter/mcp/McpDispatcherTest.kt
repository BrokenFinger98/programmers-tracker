package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.support.fixtures.aLegacyCall
import com.brokenfinger.tracker.support.fixtures.aModernCall
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aToolCallParams
import com.brokenfinger.tracker.support.fixtures.anInitializeParams
import com.brokenfinger.tracker.support.fixtures.headersFor
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The dual-era routing. MCP revision `2026-07-28` removed the `initialize` handshake, so a
 * server that serves clients shipping today *and* clients built against the current
 * specification has to answer both — and the two eras differ in their result shape, their
 * error statuses and their header rules, which is what these tests pin.
 */
class McpDispatcherTest {
    @TempDir
    lateinit var root: Path

    private lateinit var dispatcher: McpDispatcher

    @BeforeEach
    fun buildDispatcher() {
        val query = aRecordRepository(root).containing(aSubmissionRecord()).query()
        dispatcher = McpDispatcher(McpToolInvoker(query))
    }

    // ---------------------------------------------------------------- handshake era

    @Test
    fun `initialize answers with the revision the client asked for`() {
        val call = aLegacyCall("initialize", anInitializeParams("2025-06-18"))

        val result = resultOf(dispatcher.dispatch(call, McpHeaders()))

        result["protocolVersion"]!!.jsonPrimitive.content shouldBe "2025-06-18"
        result["capabilities"]!!.jsonObject.shouldContainKey("tools")
        result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe McpProtocol.NAME
        result["instructions"]!!.jsonPrimitive.content.shouldNotBeBlank()
    }

    @Test
    fun `initialize falls back to the newest handshake revision it knows`() {
        val call = aLegacyCall("initialize", anInitializeParams("2024-11-05"))

        resultOf(dispatcher.dispatch(call, McpHeaders()))["protocolVersion"]!!
            .jsonPrimitive.content shouldBe McpProtocol.LEGACY.first()
    }

    @Test
    fun `initialize survives a client that names no version at all`() {
        val call = aLegacyCall("initialize", buildJsonObject { put("protocolVersion", JsonObject(emptyMap())) })

        resultOf(dispatcher.dispatch(call, McpHeaders()))["protocolVersion"]!!
            .jsonPrimitive.content shouldBe McpProtocol.LEGACY.first()
    }

    @Test
    fun `a notification is accepted and never answered`() {
        val response = dispatcher.dispatch(aLegacyCall("notifications/initialized", id = null), McpHeaders())

        response.status shouldBe 202
        response.body.shouldBeNull()
    }

    @Test
    fun `tools list answers the catalog, with none of the modern-only fields`() {
        val result = resultOf(dispatcher.dispatch(aLegacyCall("tools/list"), McpHeaders()))

        result["tools"]!!.jsonArray.size shouldBe McpToolCatalog.NAMES.size
        result.shouldNotContainKey("resultType")
        result.shouldNotContainKey("ttlMs")
    }

    @Test
    fun `tools call runs the tool`() {
        val params = aToolCallParams("stats", buildJsonObject { put("groupBy", "verdict") })

        val result = resultOf(dispatcher.dispatch(aLegacyCall("tools/call", params), McpHeaders()))

        result["structuredContent"]!!.jsonObject["total"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `ping answers, because the handshake revisions still define it`() {
        dispatcher.dispatch(aLegacyCall("ping"), McpHeaders()).status shouldBe 200
    }

    /**
     * A handshake-era client reads a non-2xx as a transport fault and never surfaces the
     * JSON-RPC error written for it, so every protocol-level failure stays on 200 here.
     */
    @Test
    fun `an unknown method is refused on 200, where a handshake client will read it`() {
        val response = dispatcher.dispatch(aLegacyCall("warmup/plan"), McpHeaders())

        response.status shouldBe 200
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.METHOD_NOT_FOUND
    }

    @Test
    fun `an unknown tool is refused on 200 as well`() {
        val response = dispatcher.dispatch(aLegacyCall("tools/call", aToolCallParams("exam_start")), McpHeaders())

        response.status shouldBe 200
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INVALID_PARAMS
    }

    @Test
    fun `an error carries the id of the request that caused it`() {
        val response = dispatcher.dispatch(aLegacyCall("warmup/plan", id = 77), McpHeaders())

        response.body!!["id"]!!.jsonPrimitive.int shouldBe 77
    }

    // ---------------------------------------------------------------- modern era

    @Test
    fun `server discover advertises the revisions a modern client may use`() {
        val call = aModernCall("server/discover")

        val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

        result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content }
            .shouldContainExactly(McpProtocol.MODERN)
        result["capabilities"]!!.jsonObject.shouldContainKey("tools")
        result["resultType"]!!.jsonPrimitive.content shouldBe "complete"
    }

    @Test
    fun `every modern result is tagged complete and identifies the server`() {
        listOf("server/discover", "tools/list").forEach { method ->
            val call = aModernCall(method)

            val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

            result["resultType"]!!.jsonPrimitive.content shouldBe "complete"
            result["_meta"]!!.jsonObject[McpProtocol.META_SERVER_INFO].shouldNotBeNull()
        }
    }

    @Test
    fun `a modern list result carries the caching fields the revision requires`() {
        val call = aModernCall("tools/list")

        val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

        result["ttlMs"]!!.jsonPrimitive.content shouldBe McpProtocol.LIST_TTL_MS.toString()
        result["cacheScope"]!!.jsonPrimitive.content shouldBe "private"
    }

    @Test
    fun `a modern tools call runs the tool`() {
        val params = aToolCallParams("stats", buildJsonObject { put("groupBy", "language") })
        val call = aModernCall("tools/call", params)

        val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

        result["structuredContent"]!!.jsonObject["groupBy"]!!.jsonPrimitive.content shouldBe "language"
    }

    @Test
    fun `an unknown modern method is a 404 carrying the JSON-RPC code`() {
        val call = aModernCall("warmup/plan")

        val response = dispatcher.dispatch(call, headersFor(call))

        response.status shouldBe 404
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.METHOD_NOT_FOUND
    }

    @Test
    fun `a revision we do not implement is refused with the list we do`() {
        val call = aModernCall("tools/list", version = "1900-01-01")

        val response = dispatcher.dispatch(call, headersFor(call))

        response.status shouldBe 400
        val error = errorOf(response)
        error["code"]!!.jsonPrimitive.int shouldBe McpErrors.UNSUPPORTED_PROTOCOL_VERSION
        error["data"]!!.jsonObject["supported"]!!.jsonArray.map { it.jsonPrimitive.content }
            .shouldContainExactly(McpProtocol.MODERN)
        error["data"]!!.jsonObject["requested"]!!.jsonPrimitive.content shouldBe "1900-01-01"
    }

    @Test
    fun `a modern request missing its client capabilities is malformed`() {
        val call = aModernCall("tools/list", withCapabilities = false)

        val response = dispatcher.dispatch(call, headersFor(call))

        response.status shouldBe 400
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INVALID_PARAMS
    }

    // ------------------------------------------------- modern header/body agreement

    @Test
    fun `refuses a request whose protocol header disagrees with its body`() {
        val call = aModernCall("tools/list")

        val response = dispatcher.dispatch(call, headersFor(call).copy(protocolVersion = "2025-11-25"))

        response.status shouldBe 400
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.HEADER_MISMATCH
    }

    @Test
    fun `refuses a request that omits a required mirrored header`() {
        val call = aModernCall("tools/list")

        dispatcher.dispatch(call, McpHeaders(protocolVersion = null, method = "tools/list")).status shouldBe 400
        dispatcher.dispatch(call, McpHeaders(protocolVersion = McpProtocol.MODERN, method = null)).status shouldBe 400
    }

    @Test
    fun `refuses a call whose method header disagrees with its body`() {
        val call = aModernCall("tools/list")

        errorOf(dispatcher.dispatch(call, headersFor(call).copy(method = "tools/call")))["code"]!!
            .jsonPrimitive.int shouldBe McpErrors.HEADER_MISMATCH
    }

    @Test
    fun `refuses a tool call whose name header disagrees with its body`() {
        val call = aModernCall("tools/call", aToolCallParams("stats"))

        val response = dispatcher.dispatch(call, headersFor(call).copy(name = "get_problem"))

        response.status shouldBe 400
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.HEADER_MISMATCH
    }

    /** The spec requires the server to decode the Base64 sentinel before comparing. */
    @Test
    fun `accepts a tool name a conservative client sent Base64-wrapped`() {
        val params = aToolCallParams("stats", buildJsonObject { put("groupBy", "verdict") })
        val call = aModernCall("tools/call", params)
        val wrapped = "=?base64?" + java.util.Base64.getEncoder().encodeToString("stats".toByteArray()) + "?="

        dispatcher.dispatch(call, headersFor(call).copy(name = wrapped)).status shouldBe 200
    }

    @Test
    fun `refuses a Base64 name that decodes to a different tool`() {
        val call = aModernCall("tools/call", aToolCallParams("stats"))
        val wrapped = "=?base64?" + java.util.Base64.getEncoder().encodeToString("get_problem".toByteArray()) + "?="

        dispatcher.dispatch(call, headersFor(call).copy(name = wrapped)).status shouldBe 400
    }

    @Test
    fun `refuses a Base64 name that is not valid Base64`() {
        val call = aModernCall("tools/call", aToolCallParams("stats"))

        dispatcher.dispatch(call, headersFor(call).copy(name = "=?base64?not!base64?=")).status shouldBe 400
    }

    /** A handshake-era request carries none of these headers, and must not be judged by them. */
    @Test
    fun `never applies the modern header rules to a handshake request`() {
        dispatcher.dispatch(aLegacyCall("tools/list"), McpHeaders()).status shouldBe 200
    }

    private fun resultOf(response: McpHttpResponse): JsonObject = response.body!!["result"]!!.jsonObject

    private fun errorOf(response: McpHttpResponse): JsonObject = response.body!!["error"]!!.jsonObject
}
