package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.mcp.McpCall
import com.brokenfinger.tracker.adapter.mcp.McpHeaders
import com.brokenfinger.tracker.adapter.mcp.McpProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Object mothers for MCP wire traffic (dev rules §6.4). The two builders exist because the
// protocol has two eras and the difference between them IS the `_meta` block: a body with a
// per-request protocol version is a modern opening, one without it is the handshake era.

/** A handshake-era body — no `_meta`, protocol version negotiated once by `initialize`. */
fun aLegacyBody(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int? = 1): String = buildJsonObject {
    put("jsonrpc", "2.0")
    id?.let { put("id", it) }
    put("method", method)
    put("params", params)
}.toString()

/** A modern body — version and client capabilities carried on every request. */
fun aModernBody(
    method: String,
    params: JsonObject = JsonObject(emptyMap()),
    version: String? = McpProtocol.MODERN,
    withCapabilities: Boolean = true,
    id: Int? = 1,
): String = buildJsonObject {
    put("jsonrpc", "2.0")
    id?.let { put("id", it) }
    put("method", method)
    put(
        "params",
        buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
            putJsonObject("_meta") {
                version?.let { put(McpProtocol.META_PROTOCOL_VERSION, it) }
                if (withCapabilities) putJsonObject(McpProtocol.META_CLIENT_CAPABILITIES) {}
            }
        },
    )
}.toString()

fun anInitializeParams(version: String = "2025-11-25"): JsonObject = buildJsonObject {
    put("protocolVersion", version)
    putJsonObject("capabilities") {}
    putJsonObject("clientInfo") {
        put("name", "fixture-client")
        put("version", "0.0.0")
    }
}

fun aToolCallParams(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
    put("name", name)
    put("arguments", arguments)
}

fun aPromptGetParams(name: String = "exam_prep", arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
    aToolCallParams(name, arguments)

/**
 * The params of a `tools/call` or a `prompts/get` whose `arguments` is exactly [arguments], of any JSON type, and left
 * out when it is null.
 */
fun aCallParams(name: String, arguments: JsonElement?): JsonObject = buildJsonObject {
    put("name", name)
    arguments?.let { put("arguments", it) }
}

/** What a malformed client might send as `arguments`: a string, an array, a number, a boolean. None is an object. */
fun argumentsThatAreNotAnObject(): List<JsonElement> =
    listOf(JsonPrimitive("x"), JsonArray(emptyList()), JsonPrimitive(5), JsonPrimitive(true))

fun aLegacyCall(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int? = 1): McpCall =
    McpCall.from(aLegacyBody(method, params, id))

fun aModernCall(
    method: String,
    params: JsonObject = JsonObject(emptyMap()),
    version: String? = McpProtocol.MODERN,
    withCapabilities: Boolean = true,
    id: Int? = 1,
): McpCall = McpCall.from(aModernBody(method, params, version, withCapabilities, id))

/** The headers a conforming modern client mirrors out of the body it is sending. */
fun headersFor(call: McpCall): McpHeaders = McpHeaders(call.declaredVersion, call.method, call.name())
