package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey

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
}
