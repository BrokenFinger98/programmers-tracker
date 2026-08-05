package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.protocol.ChannelIdentifier

/**
 * Outbound port for holding a channel subscription open. Separated from [WatchRequestHandler]
 * so the registry's bookkeeping is testable without a socket, and so eviction has somewhere
 * to send its "stop listening to this one" (design §4.1).
 */
interface ChannelSubscriber {
    fun subscribe(identifier: ChannelIdentifier)

    fun unsubscribe(identifier: ChannelIdentifier)
}
