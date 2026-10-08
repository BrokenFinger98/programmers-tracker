package com.brokenfinger.tracker.adapter.mcp

import ch.qos.logback.classic.Level
import com.brokenfinger.tracker.support.fixtures.FailingGradingCodes
import com.brokenfinger.tracker.support.fixtures.aCallParams
import com.brokenfinger.tracker.support.fixtures.aLegacyCall
import com.brokenfinger.tracker.support.fixtures.aModernCall
import com.brokenfinger.tracker.support.fixtures.aPromptGetParams
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aToolCallParams
import com.brokenfinger.tracker.support.fixtures.anInitializeParams
import com.brokenfinger.tracker.support.fixtures.argumentsThatAreNotAnObject
import com.brokenfinger.tracker.support.fixtures.headersFor
import com.brokenfinger.tracker.support.logging.loggedWhile
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    // ---------------------------------------------------------------- prompts, handshake era

    @Test
    fun `initialize declares prompts beside tools`() {
        val result = resultOf(dispatcher.dispatch(aLegacyCall("initialize", anInitializeParams()), McpHeaders()))

        result["capabilities"]!!.jsonObject.keys.shouldContainExactlyInAnyOrder("tools", "prompts")
    }

    @Test
    fun `prompts list answers the catalog, with none of the modern-only fields`() {
        val result = resultOf(dispatcher.dispatch(aLegacyCall("prompts/list"), McpHeaders()))

        result["prompts"]!!.jsonArray.size shouldBe McpPromptCatalog.NAMES.size
        result.shouldNotContainKey("resultType")
        result.shouldNotContainKey("ttlMs")
    }

    @Test
    fun `prompts get renders exam_prep`() {
        val result = resultOf(dispatcher.dispatch(aLegacyCall("prompts/get", aPromptGetParams()), McpHeaders()))

        result["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
    }

    @Test
    fun `an unknown prompt is refused on 200 as invalid params`() {
        val response = dispatcher.dispatch(aLegacyCall("prompts/get", aPromptGetParams("warmup_plan")), McpHeaders())

        response.status shouldBe 200
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INVALID_PARAMS
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
        listOf("server/discover", "tools/list", "prompts/list").forEach { method ->
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

    // ---------------------------------------------------------------- prompts, modern era

    @Test
    fun `server discover declares prompts beside tools`() {
        val call = aModernCall("server/discover")

        val capabilities = resultOf(dispatcher.dispatch(call, headersFor(call)))["capabilities"]!!.jsonObject

        capabilities.keys.shouldContainExactlyInAnyOrder("tools", "prompts")
    }

    /** Claude Code's modern codec has no default for these, and without them it shows no prompt at all. */
    @Test
    fun `a modern prompts list is complete and carries the caching fields`() {
        val call = aModernCall("prompts/list")

        val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

        result["resultType"]!!.jsonPrimitive.content shouldBe "complete"
        result["ttlMs"]!!.jsonPrimitive.content shouldBe McpProtocol.LIST_TTL_MS.toString()
        result["cacheScope"]!!.jsonPrimitive.content shouldBe "private"
        result["prompts"]!!.jsonArray.size shouldBe McpPromptCatalog.NAMES.size
    }

    @Test
    fun `a modern prompts get renders the prompt and is complete`() {
        val call = aModernCall("prompts/get", aPromptGetParams())

        val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

        result["resultType"]!!.jsonPrimitive.content shouldBe "complete"
        result["messages"]!!.jsonArray.size shouldBe 1
    }

    @Test
    fun `an unknown modern prompt is a 400 carrying invalid params`() {
        val call = aModernCall("prompts/get", aPromptGetParams("warmup_plan"))

        val response = dispatcher.dispatch(call, headersFor(call))

        response.status shouldBe 400
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INVALID_PARAMS
    }

    @Test
    fun `refuses a prompt get whose name header disagrees with its body or is missing`() {
        val call = aModernCall("prompts/get", aPromptGetParams())

        errorOf(dispatcher.dispatch(call, headersFor(call).copy(name = "stats")))["code"]!!
            .jsonPrimitive.int shouldBe McpErrors.HEADER_MISMATCH
        dispatcher.dispatch(call, headersFor(call).copy(name = null)).status shouldBe 400
    }

    @Test
    fun `prompt arguments that are not an object are refused, 400 modern and 200 handshake`() {
        val params = aCallParams("exam_prep", JsonPrimitive("java"))

        answerModern("prompts/get", params).shouldBeTheArgumentsRefusal(onStatus = 400)
        answerLegacy("prompts/get", params).shouldBeTheArgumentsRefusal(onStatus = 200)
    }

    @Test
    fun `accepts a prompt name a conservative client sent Base64-wrapped`() {
        val call = aModernCall("prompts/get", aPromptGetParams())
        val wrapped = "=?base64?" + java.util.Base64.getEncoder().encodeToString("exam_prep".toByteArray()) + "?="

        dispatcher.dispatch(call, headersFor(call).copy(name = wrapped)).status shouldBe 200
    }

    // ---------------------------------------------------------------- a tool call's arguments

    /**
     * The CallToolRequest schema types `arguments` as an optional object (2025-11-25 and 2026-07-28), so a call that
     * carries anything else is malformed: a protocol error, `-32602`, on 400 to a modern client and on 200 to a
     * handshake one, as an unknown tool is. Read as no arguments instead, the five tools that take only optional
     * ones answered over everything on record, and `get_problem` and `stats` said that a required argument was
     * missing from a call that had sent arguments (#365).
     */
    @Test
    fun `tool arguments that are not an object are refused for every tool, 400 modern and 200 handshake`() {
        McpToolCatalog.NAMES.forEach { tool ->
            argumentsThatAreNotAnObject().forEach { notAnObject ->
                withClue("$tool with arguments $notAnObject") {
                    val params = aCallParams(tool, notAnObject)
                    answerModern("tools/call", params).shouldBeTheArgumentsRefusal(onStatus = 400)
                    answerLegacy("tools/call", params).shouldBeTheArgumentsRefusal(onStatus = 200)
                }
            }
        }
    }

    /** `arguments` is optional: absent or null narrows nothing, so `submissions` answers with the whole log. */
    @Test
    fun `absent or null tool arguments are a whole request, in both eras`() {
        listOf(aCallParams("submissions", null), aCallParams("submissions", JsonNull)).forEach { params ->
            withClue(params) {
                countIn(answerModern("tools/call", params)) shouldBe 1
                countIn(answerLegacy("tools/call", params)) shouldBe 1
            }
        }
    }

    // ---------------------------------------------------------------- a fault of ours

    /**
     * A fault behind a call is answered to that call (#355): its id, the internal error, and HTTP 200, the
     * status a handshake client reads. Nothing of the exception goes with it.
     */
    @Test
    fun `a fault of ours is answered to the handshake call that met it, on 200 with its id`() {
        val call = aLegacyCall("tools/call", aToolCallParams("repair_steps"), id = 41)

        val response = faultingDispatcher().dispatch(call, McpHeaders())

        response.shouldBeTheInternalErrorOf(id = 41)
    }

    /**
     * The modern binding assigns `-32603` no status, and the client in use reads a non-2xx as JSON-RPC only
     * when it is a 400, so a fault travels on 200 here too
     * ([[decisions/2026-10-08-a-fault-of-ours-answers-its-call-on-200]]).
     */
    @Test
    fun `a fault of ours is answered to the modern call that met it, on 200 with its id as well`() {
        val call = aModernCall("tools/call", aToolCallParams("repair_steps"), id = 42)

        val response = faultingDispatcher().dispatch(call, headersFor(call))

        response.shouldBeTheInternalErrorOf(id = 42)
    }

    /** The log is where a fault of ours is noticed, by its class: its message can carry what the tool read. */
    @Test
    fun `a fault of ours is logged once by its class and never by its message`() {
        val call = aLegacyCall("tools/call", aToolCallParams("repair_steps"))

        val logged = loggedWhile(McpFailure::class, Level.ERROR) { faultingDispatcher().dispatch(call, McpHeaders()) }

        logged.single() shouldContain "IllegalStateException"
        logged.single() shouldNotContain FAULT
    }

    // Two failed runs of one problem make a step, so repair_steps asks the code store for code, and it throws.
    private fun faultingDispatcher(): McpDispatcher {
        val runs = arrayOf(aRun(at = "2026-10-07T10:00:00+09:00"), aRun(at = "2026-10-07T10:01:00+09:00"))
        val codes = FailingGradingCodes(IllegalStateException(FAULT))
        val query = aRecordRepository(root.resolve("faulting")).containing(*runs).query(codes = codes)
        return McpDispatcher(McpToolInvoker(query))
    }

    // The id first: it is what the client matches the answer to its call by.
    private fun McpHttpResponse.shouldBeTheInternalErrorOf(id: Int) {
        body!!["id"] shouldBe JsonPrimitive(id)
        status shouldBe 200
        errorOf(this)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INTERNAL
        body.toString() shouldNotContain FAULT
    }

    private fun answerModern(method: String, params: JsonObject): McpHttpResponse {
        val call = aModernCall(method, params)
        return dispatcher.dispatch(call, headersFor(call))
    }

    private fun answerLegacy(method: String, params: JsonObject): McpHttpResponse =
        dispatcher.dispatch(aLegacyCall(method, params), McpHeaders())

    // One reader serves both paths, so a malformed `arguments` is answered alike whichever method carried it.
    // The error first: before the fix a tool call answered a result, and the comparison shows which.
    private fun McpHttpResponse.shouldBeTheArgumentsRefusal(onStatus: Int) {
        body!!["error"] shouldBe ARGUMENTS_REFUSAL
        status shouldBe onStatus
    }

    private fun countIn(response: McpHttpResponse): Int =
        resultOf(response)["structuredContent"]!!.jsonObject["count"]!!.jsonPrimitive.int

    private fun resultOf(response: McpHttpResponse): JsonObject = response.body!!["result"]!!.jsonObject

    private fun errorOf(response: McpHttpResponse): JsonObject = response.body!!["error"]!!.jsonObject

    private companion object {
        /** What a fault of ours says, standing in for what the tool had read; it must reach no answer and no log. */
        const val FAULT = "fault-marker-7c1e: what the tool had read"

        /** The JSON-RPC error an `arguments` that is not an object is answered with, on either path. */
        val ARGUMENTS_REFUSAL = buildJsonObject {
            put("code", McpErrors.INVALID_PARAMS)
            put("message", "arguments must be an object")
        }
    }
}
