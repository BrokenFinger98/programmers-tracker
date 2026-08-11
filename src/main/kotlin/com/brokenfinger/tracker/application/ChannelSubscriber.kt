package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.SubscriptionHealth

/**
 * Outbound port for holding a channel subscription open. Separated from [WatchRequestHandler]
 * so the registry's bookkeeping is testable without a socket, and so eviction has somewhere
 * to send its "stop listening to this one" (design §4.1).
 *
 * Named by [ChannelKey], not by the wire identifier: which channel to hold open is an
 * identity question, and how it is spelled on the socket is the adapter's business
 * ([[decisions/2026-08-05-protocol-dependency-direction]]).
 */
interface ChannelSubscriber {
    fun subscribe(channel: ChannelKey)

    fun unsubscribe(channel: ChannelKey)

    /**
     * Whether that subscription is actually observing right now.
     *
     * Subscribing is fire-and-forget by design — the socket outlives the request that asked
     * for it — so the only honest way to answer "is this being watched" is to ask afterwards
     * (#167). A channel this subscriber holds nothing for answers
     * [SubscriptionHealth.UNREACHABLE]: the optimistic default is the bug.
     */
    fun healthOf(channel: ChannelKey): SubscriptionHealth
}
