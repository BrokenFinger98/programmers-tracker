package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.application.WatchCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The request was rejected before anything was observed. [field] names the offending
 * field, or is null when the body itself is unusable.
 */
class InvalidWatchRequestException(val field: String?, message: String) : RuntimeException(message)

/**
 * Reads the `/watch` body by hand instead of binding it to a data class.
 *
 * Two reasons. First, the lesson id may arrive as a **string** (read from a DOM attribute
 * or the URL) or as a number (typed by hand into a curl), and a binder that accepts one and
 * 500s on the other is a trap.
 * Second, a binder's failure message is the binder's, not ours: this endpoint owes the
 * caller a stable error contract naming the exact field, because a `data-*` attribute
 * silently disappearing when Programmers changes its markup is the expected failure mode.
 *
 * Nothing is defaulted or guessed. Identifier extraction that fails must surface
 * (CLAUDE.md · design §4.1) — note the asymmetry with the protocol parsers, which are
 * deliberately lenient: there we are salvaging a record that cannot be re-created, here
 * we are refusing to start an observation we cannot trust.
 */
internal object WatchRequestPayload {
    fun parse(rawBody: String): WatchCommand {
        val body = asJsonObject(rawBody)
        return WatchCommand(
            lessonId = positiveId(body, "lessonId"),
            language = scalar(body, "language"),
        )
    }

    private fun asJsonObject(rawBody: String): JsonObject {
        if (rawBody.isBlank()) throw InvalidWatchRequestException(null, "the request body is missing")
        val parsed = runCatching { Json.parseToJsonElement(rawBody) }
            .getOrElse { throw InvalidWatchRequestException(null, "the request body is not valid JSON") }
        return parsed as? JsonObject
            ?: throw InvalidWatchRequestException(null, "the request body must be a JSON object")
    }

    private fun positiveId(body: JsonObject, field: String): Long {
        val raw = scalar(body, field)
        return raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw InvalidWatchRequestException(field, "$field must be a positive whole number, was ${quoted(raw)}")
    }

    /** Accepts the string and the number form of a value; rejects null, booleans and containers. */
    private fun scalar(body: JsonObject, field: String): String {
        val element = body[field] ?: throw InvalidWatchRequestException(field, "$field is missing")
        if (element is JsonNull) throw InvalidWatchRequestException(field, "$field is missing (it was null)")
        val primitive = element as? JsonPrimitive
            ?: throw InvalidWatchRequestException(field, "$field must be a string or a number")
        if (!primitive.isString && primitive.booleanOrNull != null) {
            throw InvalidWatchRequestException(field, "$field must be a string or a number")
        }
        return primitive.content.trim().ifBlank { throw InvalidWatchRequestException(field, "$field is blank") }
    }

    /** Echoes the caller's own value back, bounded — an unbounded echo is a log- and body-bloat vector. */
    private fun quoted(raw: String): String = "'${raw.take(ECHO_LIMIT)}'"

    private const val ECHO_LIMIT = 40
}
