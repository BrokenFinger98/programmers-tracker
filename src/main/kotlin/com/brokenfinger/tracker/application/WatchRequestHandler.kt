package com.brokenfinger.tracker.application

/**
 * Inbound port for "start observing this channel". Two consumers are planned — the web
 * adapter and, later, MCP — which is exactly the case where dev rules §1 calls for an
 * inbound interface rather than a bare service.
 *
 * The implementation owns the [SubscriptionRegistry] (LRU 8, evicted by last heartbeat,
 * live gradings pinned) and translates its [WatchResult] onto this port:
 * `Started`/`AlreadyWatching` → [WatchOutcome], `Saturated` →
 * [WatchCapacityExceededException]. That translation is the point of the port rather than
 * an accident of it — `WatchResult.Started` carries a `ChannelIdentifier`, and a protocol
 * type must not reach the web adapter (dev rules §2.1).
 */
interface WatchRequestHandler {
    /**
     * Subscribes to the channel, or refreshes recency when it is already held.
     *
     * @throws WatchCapacityExceededException when no slot can be freed.
     */
    fun watch(command: WatchCommand): WatchOutcome
}

/**
 * Every subscription slot is held by a live grading, so this request cannot be observed.
 *
 * Failing loudly is the decision: evicting a pinned slot would lose a grading result
 * permanently, and accepting the request while quietly not watching would leave the user
 * believing a solve was recorded when it was not (design §4.1,
 * [[decisions/2026-08-05-failure-taxonomy]]).
 */
class WatchCapacityExceededException(message: String) : RuntimeException(message)
