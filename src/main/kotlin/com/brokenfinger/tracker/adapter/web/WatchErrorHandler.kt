package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.application.UnresolvableProblemException
import com.brokenfinger.tracker.application.WatchCapacityExceededException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * The one error shape `/watch` ever answers with.
 *
 * `error` is a stable machine code — the extension branches on it, so it must survive
 * message rewording. `message` is for the human reading the extension's console, and
 * `field` is present only when a single field is at fault.
 *
 * ```json
 * { "error": "INVALID_REQUEST", "message": "...", "field": "challengeableType" }
 * ```
 */
@Serializable
data class WatchError(val error: String, val message: String, val field: String? = null)

/**
 * Maps the three failure kinds onto status codes, scoped to [WatchController] so it can
 * never silently swallow another endpoint's exceptions.
 *
 * Nothing derived from an exception's stack, and nothing the caller presented as a
 * credential, may reach the body — dumping request internals is forbidden (CLAUDE.md).
 */
@RestControllerAdvice(assignableTypes = [WatchController::class])
class WatchErrorHandler {
    @ExceptionHandler(InvalidWatchRequestException::class)
    fun invalidRequest(thrown: InvalidWatchRequestException): ResponseEntity<WatchError> {
        log.warn("Rejected a /watch request: {}", thrown.message)
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", thrown.message.orEmpty(), thrown.field)
    }

    /** A body Spring itself could not read. Its message quotes the payload, so it is not reused. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(thrown: HttpMessageNotReadableException): ResponseEntity<WatchError> {
        log.warn("Rejected a /watch request with an unreadable body")
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "the request body could not be read")
    }

    @ExceptionHandler(UnauthorizedWatchException::class)
    fun unauthorized(thrown: UnauthorizedWatchException): ResponseEntity<WatchError> {
        log.warn("Refused an unauthorized /watch request")
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "a valid ${WatchController.TOKEN_HEADER} is required")
    }

    /**
     * The problem page could not be turned into a channel (#114). 502, not 400: the request
     * was well formed and the failure is upstream — an expired session, or Programmers not
     * answering — so a client that retries later is doing the right thing.
     */
    @ExceptionHandler(UnresolvableProblemException::class)
    fun unresolvable(thrown: UnresolvableProblemException): ResponseEntity<WatchError> {
        log.warn("Could not resolve lesson {} to a channel", thrown.lessonId)
        return respond(HttpStatus.BAD_GATEWAY, "PROBLEM_UNRESOLVED", thrown.message.orEmpty(), "lessonId")
    }

    @ExceptionHandler(WatchCapacityExceededException::class)
    fun saturated(thrown: WatchCapacityExceededException): ResponseEntity<WatchError> {
        log.warn("Refused a /watch request, the watcher is saturated: {}", thrown.message)
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "WATCHER_SATURATED", thrown.message.orEmpty())
    }

    private fun respond(
        status: HttpStatus,
        code: String,
        message: String,
        field: String? = null,
    ): ResponseEntity<WatchError> = ResponseEntity.status(status).body(WatchError(code, message, field))

    private companion object {
        val log = LoggerFactory.getLogger(WatchErrorHandler::class.java)!!
    }
}
