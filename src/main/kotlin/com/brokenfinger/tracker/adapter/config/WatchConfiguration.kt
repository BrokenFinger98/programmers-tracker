package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.ProblemTimer
import com.brokenfinger.tracker.application.SubscriptionRegistry
import com.brokenfinger.tracker.application.WatchRequestHandler
import com.brokenfinger.tracker.application.WatchService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class WatchConfiguration {
    @Bean
    fun subscriptionRegistry() = SubscriptionRegistry()

    @Bean
    fun watchRequestHandler(
        registry: SubscriptionRegistry,
        subscriber: ChannelSubscriber,
        timer: ProblemTimer,
        clock: Clock,
    ): WatchRequestHandler = WatchService(registry, subscriber, timer, clock)
}
