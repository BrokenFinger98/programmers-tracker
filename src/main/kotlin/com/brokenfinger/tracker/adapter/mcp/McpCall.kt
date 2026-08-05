package com.brokenfinger.tracker.adapter.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One HTTP answer: the status the binding requires, and the body, absent for a 202. */
data class McpHttpResponse(val status: Int, val body: JsonObject? = null)

/**
 * A refusal that is already shaped like the answer.
 *
 * The MCP HTTP binding ties specific status codes to specific JSON-RPC codes — a header
 * mismatch is `400` with `-32020`, an unknown method on a modern request is `404` with
 * `-32601` — so carrying the two together is what keeps them from drifting apart.
 *
 * [message] is read by a language model trying to correct itself, so it says what is wrong
 * and never what the caller presented: echoing a credential back is forbidden (CLAUDE.md).
 */
class McpFailure(
    val code: Int,
    val status: Int,
    override val message: String,
    val data: JsonObject? = null,
    val id: JsonElement? = null,
) : RuntimeException(message) {
    fun response(): McpHttpResponse = McpHttpResponse(status, JsonRpc.error(id, code, message, data))

    companion object {
        fun unauthorized() = McpFailure(
            McpErrors.UNAUTHORIZED,
            401,
            "a valid ${McpController.TOKEN_HEADER} header is required",
        )

        fun forbiddenOrigin() = McpFailure(
            McpErrors.FORBIDDEN_ORIGIN,
            403,
            "this endpoint does not serve browser origins",
        )

        fun internal() = McpFailure(McpErrors.INTERNAL, 500, "the server failed to handle the request")
    }
}

/** JSON-RPC 2.0 envelopes. Result and error are mutually exclusive, so they are built here. */
object JsonRpc {
    const val VERSION = "2.0"

    fun result(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            },
        )
    }
}

/**
 * One decoded JSON-RPC message from a client.
 *
 * [declaredVersion] is the whole era decision: the specification says a dual-era server
 * "selects its behavior from how the client opens", and a request carrying the modern
 * per-request `_meta` version is the modern opening. Its absence means the legacy
 * handshake, and nothing else has to be remembered between requests.
 */
data class McpCall(
    val method: String,
    val id: JsonElement?,
    val params: JsonObject,
    val declaredVersion: String?,
    val declaresClientCapabilities: Boolean,
) {
    /** A notification carries no id at all, and the receiver must not answer it. */
    fun isNotification(): Boolean = id == null

    fun isModern(): Boolean = declaredVersion != null

    // Every reader below casts instead of coercing. A member of the wrong JSON type is a
    // malformed request, and it has to come back as an absent value we can refuse cleanly
    // rather than as an exception that would surface to the client as an internal error.
    fun toolName(): String? = (params["name"] as? JsonPrimitive)?.contentOrNull

    fun arguments(): JsonObject = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

    fun stringArgument(name: String): String? = (arguments()[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        // Lenient about shape, strict about the envelope: a body that is not JSON at all is a
        // parse error, but a member we do not recognise is simply carried along.
        private val format = Json { ignoreUnknownKeys = true }

        fun from(body: String): McpCall {
            val root = parse(body)
            val method = (root["method"] as? JsonPrimitive)?.contentOrNull
                ?: throw McpFailure(McpErrors.INVALID_REQUEST, 400, "a JSON-RPC request needs a method")
            val params = root["params"] as? JsonObject ?: JsonObject(emptyMap())
            return McpCall(method, identifierOf(root), params, versionIn(params), capabilitiesIn(params))
        }

        private fun parse(body: String): JsonObject = runCatching { format.parseToJsonElement(body).jsonObject }
            .getOrElse { throw McpFailure(McpErrors.PARSE, 400, "the request body is not a JSON-RPC object") }

        // An explicit `"id": null` is forbidden by the spec and is not a notification either,
        // so it is treated as the absence it looks like rather than answered with a null id.
        private fun identifierOf(root: JsonObject): JsonElement? = root["id"]?.takeIf { it !is JsonNull }

        private fun metaIn(params: JsonObject): JsonObject? = params["_meta"] as? JsonObject

        private fun versionIn(params: JsonObject): String? =
            (metaIn(params)?.get(McpProtocol.META_PROTOCOL_VERSION) as? JsonPrimitive)?.contentOrNull

        private fun capabilitiesIn(params: JsonObject): Boolean =
            metaIn(params)?.containsKey(McpProtocol.META_CLIENT_CAPABILITIES) == true
    }
}
