package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SessionState
import com.brokenfinger.tracker.domain.SubscriptionHealth

/**
 * Inbound port for "start observing this channel". Two consumers are planned — the web
 * adapter and, later, MCP — which is exactly the case where dev rules §1 calls for an
 * inbound interface rather than a bare service.
 *
 * The implementation owns the [SubscriptionRegistry] (LRU 8, evicted by last heartbeat,
 * live gradings pinned) and translates its [WatchResult] onto this port:
 * `Started`/`AlreadyWatching` → [WatchOutcome], `Saturated` →
 * [WatchCapacityExceededException]. That translation is the point of the port rather than
 * an accident of it — `WatchResult.Started` carries the evicted `ChannelKey`, which is
 * bookkeeping the web adapter has no use for and should not have to name.
 */
interface WatchRequestHandler {
    /**
     * Subscribes to the channel, or refreshes recency when it is already held.
     *
     * @throws WatchCapacityExceededException when no slot can be freed.
     */
    suspend fun watch(command: WatchCommand): WatchStatus
}

/**
 * What the request did, and whether the channel is actually being observed as a result.
 *
 * The two are separate answers and were conflated until #167: `started` said the request was
 * accepted, and a caller reasonably read it as "this problem is now being watched". A
 * subscription refused on the socket produced exactly the same answer.
 */
data class WatchStatus(
    val outcome: WatchOutcome,
    val health: SubscriptionHealth,
    /**
     * Whether the session cookie still authenticates — which the socket cannot answer.
     *
     * An unauthenticated subscription is confirmed and pinged normally and receives nothing
     * (protocol doc §15.3), so [health] can read LIVE while every grading is lost. This is the
     * field that catches that (#179).
     */
    val session: SessionState,
)

/**
 * Every subscription slot is held by a live grading, so this request cannot be observed.
 *
 * Failing loudly is the decision: evicting a pinned slot would lose a grading result
 * permanently, and accepting the request while quietly not watching would leave the user
 * believing a solve was recorded when it was not (design §4.1,
 * [[decisions/2026-08-05-failure-taxonomy]]).
 */
class WatchCapacityExceededException(message: String) : RuntimeException(message)

/**
 * The problem page could not be turned into a channel, so there is nothing to subscribe to
 * (#114).
 *
 * Answered rather than swallowed. The commonest cause by far is an expired session cookie —
 * the page redirects to sign-in and carries no identifiers — and the user needs to hear that
 * while they are looking at the problem, not discover it later from an empty record.
 */
class UnresolvableProblemException(val lessonId: Long) :
    RuntimeException(
        "lesson $lessonId could not be resolved to a channel — its problem page carried no " +
            "identifiers. An expired session in .ps/session is the usual cause.",
    )
