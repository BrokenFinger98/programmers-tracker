package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.application.WatchCommand
import com.brokenfinger.tracker.application.WatchOutcome
import com.brokenfinger.tracker.application.WatchRequestHandler
import kotlinx.serialization.Serializable
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** What `/watch` answers on success. `status` distinguishes a new subscription from a heartbeat refresh. */
@Serializable
data class WatchAccepted(val status: String, val lessonId: Long, val challengeableId: Long) {
    companion object {
        fun of(command: WatchCommand, outcome: WatchOutcome) = WatchAccepted(
            status = outcome.name.lowercase(),
            lessonId = command.lessonId,
            challengeableId = command.challengeableId,
        )
    }
}

/**
 * `POST /watch` — the extension tells the server which problem channel to observe
 * (design §4.1). It is called once when a problem page opens, again on every language-tab
 * switch (the codes key changes), and again on every 30-second heartbeat, so it is
 * idempotent: a repeat for a channel already held refreshes recency and nothing more.
 *
 * The body is taken as raw text and validated by [WatchRequestPayload]; failures are
 * turned into the error contract by [WatchErrorHandler]. Errors never carry a stack trace
 * and never echo a credential.
 */
@RestController
class WatchController(private val watcher: WatchRequestHandler, private val token: WatchToken) {
    @PostMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun watch(
        @RequestHeader(name = TOKEN_HEADER, required = false) presented: String?,
        @RequestBody(required = false) rawBody: String?,
    ): WatchAccepted {
        token.verify(presented)
        val command = WatchRequestPayload.parse(rawBody.orEmpty())
        return WatchAccepted.of(command, watcher.watch(command))
    }

    companion object {
        const val PATH = "/watch"

        /** Local authorization header. Not `Authorization`: this is not a Programmers credential. */
        const val TOKEN_HEADER = "X-Tracker-Token"
    }
}
