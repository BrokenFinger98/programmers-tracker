package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.SubscriptionRegistry
import com.brokenfinger.tracker.application.WatchRequestHandler
import com.brokenfinger.tracker.application.WatchService
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class WatchConfiguration {
    @Bean
    fun subscriptionRegistry() = SubscriptionRegistry()

    @Bean
    fun watchRequestHandler(registry: SubscriptionRegistry, subscriber: ChannelSubscriber): WatchRequestHandler =
        WatchService(registry, subscriber, Clock.systemUTC())

    /**
     * The cable-backed subscriber is not built yet, so this one only records the intent.
     *
     * It is deliberately not a silent no-op: `/watch` tracking works and is testable, but
     * nothing is listening on the socket yet, and a warning per call is what keeps that
     * visible instead of letting the endpoint look finished.
     */
    @Bean
    fun channelSubscriber(): ChannelSubscriber = UnconnectedChannelSubscriber()
}

class UnconnectedChannelSubscriber : ChannelSubscriber {
    override fun subscribe(identifier: ChannelIdentifier) = warn("subscribe", identifier)

    override fun unsubscribe(identifier: ChannelIdentifier) = warn("unsubscribe", identifier)

    private fun warn(action: String, identifier: ChannelIdentifier) {
        logger.warn(
            "Registry asked to {} lesson {} but no cable connection is attached yet — " +
                "nothing is being observed",
            action,
            identifier.lessonId.value,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(UnconnectedChannelSubscriber::class.java)
    }
}
