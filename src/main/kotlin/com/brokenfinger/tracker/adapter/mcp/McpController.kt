package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.adapter.web.UnauthorizedWatchException
import com.brokenfinger.tracker.adapter.web.WatchToken
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /mcp` — the Streamable HTTP endpoint of design §7.
 *
 * Streamable HTTP rather than stdio because this is a resident process: stdio is
 * one server per client process, and the server already holds a live cable subscription
 * and an exclusive lock on the record repository, so a second copy per client is not a
 * thing that can exist. Every answer here is a single JSON object — no tool streams
 * progress, so an SSE body would be ceremony — which the binding explicitly permits.
 *
 * **Authorization is the same local token as `/watch`, and for a stronger reason.** That
 * endpoint's comment says loopback is shared with every other process on the machine;
 * this one reads the user's entire solving history, so the bar cannot be lower. One
 * process holds one credential — a second token would double what the user must copy and
 * protect nothing extra, since both guard the same process.
 *
 * The endpoint never sees a Programmers session cookie. It logs nothing on the normal path
 * at all — not the request, not the answer, and at no level — because every answer it
 * gives is a piece of the user's solving history. The one thing written is a fault of
 * ours, and even that carries no request content.
 */
@RestController
class McpController(
    private val dispatcher: McpDispatcher,
    private val token: WatchToken,
    private val origins: McpAllowedOrigins,
) {
    @PostMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun exchange(
        @RequestHeader(name = TOKEN_HEADER, required = false) presented: String?,
        @RequestHeader(name = "Origin", required = false) origin: String?,
        @RequestHeader(name = "MCP-Protocol-Version", required = false) protocolVersion: String?,
        @RequestHeader(name = "Mcp-Method", required = false) method: String?,
        @RequestHeader(name = "Mcp-Name", required = false) name: String?,
        @RequestBody(required = false) rawBody: String?,
    ): ResponseEntity<String> {
        val headers = McpHeaders(protocolVersion, method, name)
        return answer(runCatching { handle(origin, presented, rawBody, headers) }.getOrElse(::refused))
    }

    /**
     * The modern revision removed both the standalone SSE stream and protocol-level
     * sessions, and tells a server that implements only it to answer their verbs with 405
     * rather than a silence a client would wait out.
     */
    @RequestMapping(PATH, method = [RequestMethod.GET, RequestMethod.DELETE])
    fun notStreamed(): ResponseEntity<String> = answer(
        McpHttpResponse(
            HttpStatus.METHOD_NOT_ALLOWED.value(),
            JsonRpc.error(null, McpErrors.INVALID_REQUEST, "this endpoint accepts POST only"),
        ),
    )

    private fun handle(origin: String?, presented: String?, rawBody: String?, headers: McpHeaders): McpHttpResponse {
        origins.verify(origin)
        token.verify(presented)
        return dispatcher.dispatch(McpCall.from(rawBody.orEmpty()), headers)
    }

    // A fault of ours is answered as one. It never carries the exception outward: dumping
    // request internals or a stack trace into a response is forbidden (CLAUDE.md).
    private fun refused(thrown: Throwable): McpHttpResponse = when (thrown) {
        is McpFailure -> thrown.response()
        is UnauthorizedWatchException -> McpFailure.unauthorized().response()
        else -> internal(thrown)
    }

    private fun internal(thrown: Throwable): McpHttpResponse {
        log.error("An MCP request failed", thrown)
        return McpFailure.internal().response()
    }

    private fun answer(response: McpHttpResponse): ResponseEntity<String> = ResponseEntity
        .status(response.status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(response.body?.toString())

    companion object {
        const val PATH = "/mcp"

        /** The same local credential as `/watch`; one process, one token. */
        const val TOKEN_HEADER = "X-Tracker-Token"

        private val log = LoggerFactory.getLogger(McpController::class.java)!!
    }
}
