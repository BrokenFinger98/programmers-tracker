package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.application.SessionHealth
import com.brokenfinger.tracker.protocol.ManualFileSessionProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /session` — the sensor hands over the browser's `_session_production` (#332).
 *
 * Loopback-only like `/watch`, gated by the same token, and it answers with a shape, never the
 * value: whether the credential changed, whether it was written to the session file, and what
 * the session looks like *with it* — the probe runs at once, so the sensor knows in the same
 * round trip whether the cookie it found is any good.
 *
 * Reopening the observations is not done here. The next heartbeat does it through
 * `reauthenticate` ([[decisions/2026-09-29-a-replaced-credential-reopens-the-observation]]), and
 * the sensor sends that heartbeat right after a hand-off that changed something, so nothing
 * waits thirty seconds.
 */
@RestController
class SessionController(
    private val token: WatchToken,
    private val sessions: ManualFileSessionProvider,
    private val health: SessionHealth,
) {
    @PostMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun replace(
        @RequestHeader(WatchController.TOKEN_HEADER, required = false) presented: String?,
        @RequestHeader("Origin", required = false) origin: String?,
        @RequestBody(required = false) rawBody: String?,
    ): SessionAccepted {
        token.verify(presented)
        refuseWebOrigins(origin)
        val replacement = sessions.replace(SessionPayload.parse(rawBody.orEmpty()))
        if (replacement.changed) health.credentialReplaced()
        val state = runBlocking { health.state() }
        log.info(
            "The sensor handed over a session cookie: changed={}, persisted={}, session={}",
            replacement.changed,
            replacement.persisted,
            state.name.lowercase(),
        )
        return SessionAccepted(
            accepted = true,
            changed = replacement.changed,
            persisted = replacement.persisted,
            session = state.name.lowercase(),
        )
    }

    /**
     * A request carrying a web origin is a page, and a page has no business handing over this
     * credential — defence in depth beside the token, as `/mcp` refuses origins. The sensor's own
     * requests carry the extension's origin, which is admitted.
     */
    private fun refuseWebOrigins(origin: String?) {
        if (origin == null) return
        if (EXTENSION_ORIGINS.any(origin::startsWith)) return
        throw ForbiddenOriginException()
    }

    companion object {
        const val PATH = "/session"
        private val log = LoggerFactory.getLogger(SessionController::class.java)
        private val EXTENSION_ORIGINS = listOf("chrome-extension://", "moz-extension://", "safari-web-extension://")
    }
}

/** The caller is a web page. Carries no detail on purpose. */
class ForbiddenOriginException : RuntimeException("a web origin may not hand over a session")

/** The answer carries what happened and how the session looks now — and never the value. */
@Serializable
data class SessionAccepted(val accepted: Boolean, val changed: Boolean, val persisted: Boolean, val session: String)

/**
 * `{ "cookie": "<bare value or name=value>" }`, read by hand for the same reasons as
 * [WatchRequestPayload]: a stable error naming the field, and no binder message quoting a
 * credential back at the caller.
 */
internal object SessionPayload {
    private const val FIELD = "cookie"
    private const val MAX_LENGTH = 4096

    fun parse(rawBody: String): String {
        val body = runCatching { Json.parseToJsonElement(rawBody).jsonObject }
            .getOrElse { throw InvalidWatchRequestException(null, "the request body must be a JSON object") }
        val element = body[FIELD] ?: throw InvalidWatchRequestException(FIELD, "$FIELD is required")
        // JsonNull is a JsonPrimitive whose content is the word "null"; only a real string will do.
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw InvalidWatchRequestException(FIELD, "$FIELD must be a string")
        }
        val value = primitive.content.trim()
        if (value.isEmpty()) throw InvalidWatchRequestException(FIELD, "$FIELD must not be blank")
        if (value.length > MAX_LENGTH) {
            throw InvalidWatchRequestException(FIELD, "$FIELD is longer than a cookie can be")
        }
        if (value.any { it.isISOControl() }) {
            throw InvalidWatchRequestException(FIELD, "$FIELD must not contain control characters")
        }
        // A cookie value is cookie-octets (RFC 6265): no spaces, quotes, commas, semicolons or
        // backslashes — so nothing that could smuggle a second cookie into the outbound header.
        if (!COOKIE_SHAPE.matches(value)) {
            throw InvalidWatchRequestException(
                FIELD,
                "$FIELD must be a bare cookie value, or _session_production=<value>",
            )
        }
        return value
    }

    private val COOKIE_SHAPE = Regex("^(?:_session_production=)?[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]+$")
}
