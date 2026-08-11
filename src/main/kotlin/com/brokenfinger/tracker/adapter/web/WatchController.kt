package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.application.RecordQuery
import com.brokenfinger.tracker.application.WatchCommand
import com.brokenfinger.tracker.application.WatchRequestHandler
import com.brokenfinger.tracker.application.WatchStatus
import com.brokenfinger.tracker.domain.SubmissionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * What `/watch` answers on success. `status` distinguishes a new subscription from a
 * heartbeat refresh; `lessonId` echoes what the caller named, which is now all they sent
 * (#114) — the channel identifiers the server resolved for itself are its own business.
 *
 * [subscription] is the field that stops `status` from being a promise it cannot keep.
 * Subscribing is fire-and-forget, so `started` only ever meant "the request was accepted";
 * a socket the judge refused answered exactly the same way, and the badge stayed green while
 * every grading was lost (#167).
 */
@Serializable
data class WatchAccepted(
    val status: String,
    val lessonId: Long,
    val language: String,
    /** Whether the channel is actually being observed: pending · live · rejected · unreachable. */
    val subscription: String,
    /**
     * Whether the session cookie still authenticates: alive · expired · unknown.
     *
     * Separate from [subscription] because the socket cannot answer it. An unauthenticated
     * subscription is confirmed and pinged normally and receives nothing, so `subscription`
     * reads `live` while nothing is recorded (protocol §15.3, #179).
     */
    val session: String,
    /** The newest grading recorded for this problem. Absent when there is none (#156). */
    val lastRecord: RecordedGrading? = null,
) {
    companion object {
        fun of(command: WatchCommand, status: WatchStatus, last: SubmissionRecord?) = WatchAccepted(
            status = status.outcome.name.lowercase(),
            lessonId = command.lessonId,
            language = command.language,
            subscription = status.health.name.lowercase(),
            session = status.session.name.lowercase(),
            lastRecord = last?.let(RecordedGrading::from),
        )
    }
}

/**
 * What the server actually wrote down, for the badge to compare against what the page showed.
 *
 * [verdict] is absent when the grading was not classified, and that absence is the point: an
 * UNKNOWN here is the one signal that the learner saw a result the server could not record as
 * one. Everything else a client might want is in the MCP tools; this is deliberately the
 * smallest answer that makes the badge honest.
 */
@Serializable
data class RecordedGrading(
    val action: String,
    val outcome: String,
    val verdict: String? = null,
    val passed: Int,
    val total: Int,
    val at: String,
) {
    companion object {
        fun from(record: SubmissionRecord) = RecordedGrading(
            action = record.action.name.lowercase(),
            outcome = record.outcome.name,
            verdict = record.verdict?.name,
            passed = record.tcSummary.passed,
            total = record.tcSummary.total,
            at = record.ts.toString(),
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
class WatchController(
    private val watcher: WatchRequestHandler,
    private val token: WatchToken,
    private val records: RecordQuery,
) {
    @PostMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun watch(
        @RequestHeader(name = TOKEN_HEADER, required = false) presented: String?,
        @RequestBody(required = false) rawBody: String?,
    ): WatchAccepted {
        token.verify(presented)
        val command = WatchRequestPayload.parse(rawBody.orEmpty())
        // The resolver reaches the network, so this handler blocks — on a virtual thread,
        // which is what the inbound half is for (decisions/2026-08-05-backend-stack).
        val status = runBlocking { watcher.watch(command) }
        // Read after the subscription, so a heartbeat that arrives while a grading is settling
        // reports the record once it exists rather than a stale one from before it.
        return WatchAccepted.of(command, status, records.lastRecordOf(command.lessonId))
    }

    companion object {
        const val PATH = "/watch"

        /** Local authorization header. Not `Authorization`: this is not a Programmers credential. */
        const val TOKEN_HEADER = "X-Tracker-Token"
    }
}
