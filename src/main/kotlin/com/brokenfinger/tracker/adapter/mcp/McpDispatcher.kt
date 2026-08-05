package com.brokenfinger.tracker.adapter.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** The headers the modern binding mirrors out of the body, so intermediaries can route. */
data class McpHeaders(val protocolVersion: String? = null, val method: String? = null, val name: String? = null)

/**
 * Routes one call, in whichever era the client opened with.
 *
 * The two eras differ in more than method names, which is why they are separate paths
 * rather than one path with flags: a modern result carries `resultType` and the server's
 * identity, its errors carry the HTTP status the binding assigns them (`404` for an
 * unknown method, `400` for a bad version), and its headers must agree with its body. A
 * legacy answer carries none of that and keeps every JSON-RPC-level failure on `200`,
 * because a handshake-era client reads a non-2xx as a transport fault and never sees the
 * error we wrote for it.
 */
class McpDispatcher(private val tools: McpToolInvoker) {
    fun dispatch(call: McpCall, headers: McpHeaders): McpHttpResponse =
        runCatching { route(call, headers) }.getOrElse { refused(it, call) }

    private fun route(call: McpCall, headers: McpHeaders): McpHttpResponse {
        // Answering a notification is forbidden, and nothing a client notifies us of changes
        // an answer we would give, so accepting it is the whole handling.
        if (call.isNotification()) return McpHttpResponse(202)
        if (call.isModern()) return modern(call, headers)
        return McpHttpResponse(200, JsonRpc.result(call.id, legacy(call)))
    }

    private fun modern(call: McpCall, headers: McpHeaders): McpHttpResponse {
        verifyHeaders(call, headers)
        verifyVersion(call)
        verifyClientCapabilities(call)
        return McpHttpResponse(200, JsonRpc.result(call.id, completed(answerModern(call))))
    }

    private fun answerModern(call: McpCall): JsonObject = when (call.method) {
        DISCOVER -> discovery()
        TOOLS_LIST -> cacheable(toolList())
        TOOLS_CALL -> tools.call(call.toolName(), call.arguments())
        else -> throw McpFailure(McpErrors.METHOD_NOT_FOUND, 404, "this server does not implement ${call.method}")
    }

    private fun legacy(call: McpCall): JsonObject = when (call.method) {
        INITIALIZE -> initialization(call)
        PING -> JsonObject(emptyMap())
        TOOLS_LIST -> toolList()
        TOOLS_CALL -> tools.call(call.toolName(), call.arguments())
        else -> throw McpFailure(McpErrors.METHOD_NOT_FOUND, 404, "this server does not implement ${call.method}")
    }

    private fun initialization(call: McpCall): JsonObject = buildJsonObject {
        put("protocolVersion", McpProtocol.negotiatedLegacy(requestedVersion(call)))
        put("capabilities", capabilities())
        put("serverInfo", identity())
        put("instructions", INSTRUCTIONS)
    }

    private fun discovery(): JsonObject = cacheable(
        buildJsonObject {
            putJsonArray("supportedVersions") { McpProtocol.MODERN_SUPPORTED.forEach { add(it) } }
            put("capabilities", capabilities())
            put("instructions", INSTRUCTIONS)
        },
    )

    private fun toolList(): JsonObject = buildJsonObject { put("tools", McpToolCatalog.definitions()) }

    private fun capabilities(): JsonObject = buildJsonObject { putJsonObject("tools") {} }

    private fun identity(): JsonObject = buildJsonObject {
        put("name", McpProtocol.NAME)
        put("version", McpProtocol.version())
    }

    /** Modern results are tagged and identified; a client must be able to read both. */
    private fun completed(result: JsonObject): JsonObject = buildJsonObject {
        put("resultType", "complete")
        result.forEach { (key, value) -> put(key, value) }
        putJsonObject("_meta") { put(McpProtocol.META_SERVER_INFO, identity()) }
    }

    // Only the modern revision defines these, so they are added on that path alone.
    private fun cacheable(result: JsonObject): JsonObject = buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        put("ttlMs", McpProtocol.LIST_TTL_MS)
        put("cacheScope", McpProtocol.CACHE_SCOPE)
    }

    private fun verifyHeaders(call: McpCall, headers: McpHeaders) {
        mismatchUnless(headers.protocolVersion == call.declaredVersion, "MCP-Protocol-Version")
        mismatchUnless(headers.method == call.method, "Mcp-Method")
        if (call.method != TOOLS_CALL) return
        mismatchUnless(decoded(headers.name) == call.toolName(), "Mcp-Name")
    }

    private fun verifyVersion(call: McpCall) {
        if (call.declaredVersion in McpProtocol.MODERN_SUPPORTED) return
        throw McpFailure(
            McpErrors.UNSUPPORTED_PROTOCOL_VERSION,
            400,
            "Unsupported protocol version",
            buildJsonObject {
                putJsonArray("supported") { McpProtocol.MODERN_SUPPORTED.forEach { add(it) } }
                put("requested", call.declaredVersion)
            },
        )
    }

    private fun verifyClientCapabilities(call: McpCall) {
        if (call.declaresClientCapabilities) return
        throw McpFailure(
            McpErrors.INVALID_PARAMS,
            400,
            "_meta must carry ${McpProtocol.META_CLIENT_CAPABILITIES}",
        )
    }

    private fun mismatchUnless(agrees: Boolean, header: String) {
        if (agrees) return
        throw McpFailure(McpErrors.HEADER_MISMATCH, 400, "$header is missing or disagrees with the request body")
    }

    // Names outside the header-safe ASCII set arrive Base64-wrapped in a sentinel, and the
    // spec requires the server to decode before comparing. Ours are all plain, so this only
    // ever matters for a conforming client being conservative.
    private fun decoded(header: String?): String? {
        if (header == null || !header.startsWith(SENTINEL) || !header.endsWith(SENTINEL_END)) return header
        val encoded = header.substring(SENTINEL.length, header.length - SENTINEL_END.length)
        return runCatching { String(Base64.getDecoder().decode(encoded), UTF_8) }.getOrNull()
    }

    // Cast rather than coerce: a client that sends an object where a version belongs is
    // malformed, and reading it must produce "no version" rather than throw us into a 500.
    private fun requestedVersion(call: McpCall): String? =
        (call.params["protocolVersion"] as? JsonPrimitive)?.contentOrNull

    private fun refused(thrown: Throwable, call: McpCall): McpHttpResponse {
        val failure = thrown as? McpFailure ?: throw thrown
        val status = failure.status.takeIf { call.isModern() } ?: 200
        return McpHttpResponse(status, JsonRpc.error(call.id, failure.code, failure.message, failure.data))
    }

    private companion object {
        const val INITIALIZE = "initialize"
        const val PING = "ping"
        const val DISCOVER = "server/discover"
        const val TOOLS_LIST = "tools/list"
        const val TOOLS_CALL = "tools/call"

        const val SENTINEL = "=?base64?"
        const val SENTINEL_END = "?="

        const val INSTRUCTIONS =
            "Reads one learner's own Programmers solving history from a local record repository. " +
                "Every tool returns stored records and counts; none of them interprets, ranks or " +
                "advises, and a value that was never recorded is absent rather than filled in."
    }
}
