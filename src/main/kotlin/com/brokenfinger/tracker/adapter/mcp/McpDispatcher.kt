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
 *
 * A fault of ours is answered here too, to the call that met it: its id, `-32603`, and `200`
 * in both eras, because the binding assigns that code no status ([McpFailure.internal]).
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
        TOOLS_CALL -> tools.call(call.name(), call.arguments())
        PROMPTS_LIST -> cacheable(promptList())
        PROMPTS_GET -> McpPromptCatalog.get(call.name(), call.strictArguments())
        else -> throw McpFailure(McpErrors.METHOD_NOT_FOUND, 404, "this server does not implement ${call.method}")
    }

    private fun legacy(call: McpCall): JsonObject = when (call.method) {
        INITIALIZE -> initialization(call)
        PING -> JsonObject(emptyMap())
        TOOLS_LIST -> toolList()
        TOOLS_CALL -> tools.call(call.name(), call.arguments())
        PROMPTS_LIST -> promptList()
        PROMPTS_GET -> McpPromptCatalog.get(call.name(), call.strictArguments())
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

    private fun promptList(): JsonObject = buildJsonObject { put("prompts", McpPromptCatalog.definitions()) }

    // Fixed at compile time, so neither list ever changes under a client: no `listChanged`.
    private fun capabilities(): JsonObject = buildJsonObject {
        putJsonObject("tools") {}
        putJsonObject("prompts") {}
    }

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
        if (call.method !in NAMED) return
        mismatchUnless(decoded(headers.name) == call.name(), "Mcp-Name")
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

    // Every refusal is answered to the call it met, by its id and in its era. A fault of ours too (#355),
    // which used to leave here and be answered by the controller with no id, on HTTP 500.
    private fun refused(thrown: Throwable, call: McpCall): McpHttpResponse {
        val failure = McpFailure.from(thrown)
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
        const val PROMPTS_LIST = "prompts/list"
        const val PROMPTS_GET = "prompts/get"

        /** The methods whose `params.name` the modern binding mirrors into `Mcp-Name`. */
        private val NAMED = setOf(TOOLS_CALL, PROMPTS_GET)

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
         *
         * **It stays within 2,000 characters, in the order a cut would cost least.** Claude Code cuts
         * server instructions at 2,048 and keeps the head, so an overrun loses the end without a word:
         * at 2,921 characters the `codeLate` reading, the one about `statement` and `kind`, what the
         * data cannot say and the hand-over were all past the cut. What the server is and the
         * hand-over come first, the lists after them, and `McpInstructionsTest` pins the budget.
         */
        val INSTRUCTIONS =
            """
            Reads one learner's own Programmers solving history from a local record repository.
            Every tool returns stored records and counts; none of them interprets, ranks or
            advises, and a value that was never recorded is absent rather than filled in.

            The server counts and names nothing — no weakness, no next step — and not for lack of
            ability: deciding that is the reader's job. Cite numbers; say when records do not
            support a claim.

            WHICH TOOL ANSWERS WHAT
            - submissions: the whole log, narrowed by date or verdict.
            - get_problem: one lesson in full — gradings, testcases, compiler output, statement;
              `include` adds code.
            - stats: counts per verdict, language, problem, part or level.
            - list_problems: the catalog joined against the records; the only tool that can say
              "untouched".
            - review_queue: passes due for re-solving.
            - slow_passes: passes ranked by their slowest testcase.
            - repair_steps: each grading that did not pass, the next one in its language, and the
              code diff.

            READINGS THAT ARE EASY TO GET WRONG
            - A run is not an attempt; `stats` counts submits only.
            - `elapsedSec` is wall clock since first opening, sleep and days away included;
              `sensor.focusedSec` is time in front of it. Use focusedSec for effort.
            - Absent is not zero: a missing `level` is unknown, not 0; an absent `statement` or
              `kind` says nothing about the problem.
            - `incompleteHistory` means gradings were captured that no record represents: any
              conclusion must say the denominator has holes.
            - The catalog is a snapshot; a newer problem is missing, not "untouched".
            - A repair step shows what changed, not what was wrong. Run code is kept by tracker
              versions from 2026-10-07 on; `noDiff` says why a step has no diff, and `codeLate`
              marks code that may belong to the next grading.

            WHAT THIS DATA CANNOT SAY
            Nothing about other learners (no cohort, so "slow" means slow against this learner's
            own passes), why a grading failed beyond the judge's output, or time away from the tab.
            """.trimIndent()
    }
}
