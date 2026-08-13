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

    // Not private: INSTRUCTIONS is the only thing the model reads before calling anything, so it
    // is asserted on directly rather than scraped out of an initialize response (#286).
    internal companion object {
        const val INITIALIZE = "initialize"
        const val PING = "ping"
        const val DISCOVER = "server/discover"
        const val TOOLS_LIST = "tools/list"
        const val TOOLS_CALL = "tools/call"

        const val SENTINEL = "=?base64?"
        const val SENTINEL_END = "?="

        /**
         * What the model is handed before it calls anything (#286).
         *
         * `docs/mcp.md` warns a **human** about every way these records mislead. The model never
         * reads that file — this string is all it gets, and it used to be one sentence. So the
         * warnings live here now, in the three kinds that are not the server interpreting
         * anything: which tool answers which question, which readings are easy to get wrong, and
         * what this data cannot say at all.
         *
         * Telling the reader that `elapsedSec` includes sleep is a fact about a field. Telling it
         * the learner is weak at graphs would be a judgement about a person, and belongs nowhere
         * near here ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]).
         */
        val INSTRUCTIONS =
            """
            Reads one learner's own Programmers solving history from a local record repository.
            Every tool returns stored records and counts; none of them interprets, ranks or
            advises, and a value that was never recorded is absent rather than filled in.

            WHICH TOOL ANSWERS WHAT
            - submissions: the whole log, newest first, narrowed by date or verdict.
            - get_problem: one lesson in full — every grading, per-testcase results, compiler
              output, and the problem's own statement.
            - stats: counts per verdict, language or problem. Counts only.
            - list_problems: the shipped catalog joined against the records. The only tool that
              can say "untouched" — records alone cannot tell never-tried from tried-and-failed.
            - review_queue: passes due for re-solving, most overdue first.
            - slow_passes: passed problems ranked by their slowest testcase.

            READINGS THAT ARE EASY TO GET WRONG
            - A run is not an attempt. Pressing Run is how code gets written; `stats` counts
              submits only, and `get_problem` splits them into submissionCount and runCount.
            - `elapsedSec` is wall clock since the problem was first opened — sleep, other work
              and days between sessions included. `sensor.focusedSec` is time actually in front
              of it. One measured record carries elapsedSec 77251 beside focusedSec 37. Use
              focusedSec for effort; they differ by orders of magnitude and neither is wrong.
            - Absent is not zero. A field that was never recorded is left out, so a missing
              `level` means unknown rather than level 0.
            - `incompleteHistory` in an answer means gradings were captured that no record
              represents. Every tool reads that same history, so any conclusion drawn from it
              must say the denominator has holes.
            - The catalog is a snapshot we do not own. A problem published after it was built is
              simply missing, which is not the same as never attempted.
            - `statement` and `kind` are absent for problems recorded before the server began
              keeping them. Their absence says nothing about the problem.

            WHAT THIS DATA CANNOT SAY
            Nothing about other learners — there is no cohort here, so "slow" only ever means
            slow against this learner's own other solutions. Nothing about why a submission
            failed beyond what the judge returned. Nothing about time spent away from the tab.

            THE PART THAT IS YOURS
            The server counts and names nothing. It will not tell you which of these numbers is
            a weakness, what to practise next, or whether a pass was lucky — not because it
            cannot, but because deciding that is the reader's job and yours. Say what the records
            support, say when they do not support it, and prefer citing a number over asserting
            a pattern.
            """.trimIndent()
    }
}
