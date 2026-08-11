package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.ProblemIdentityResolver
import com.brokenfinger.tracker.application.ProblemTimer
import com.brokenfinger.tracker.application.SessionHealth
import com.brokenfinger.tracker.application.SubscriptionRegistry
import com.brokenfinger.tracker.application.WatchRequestHandler
import com.brokenfinger.tracker.application.WatchService
import com.brokenfinger.tracker.protocol.PageProblemIdentityResolver
import com.brokenfinger.tracker.protocol.PageSource
import com.brokenfinger.tracker.protocol.SessionActivityProbe
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class WatchConfiguration {
    @Bean
    fun subscriptionRegistry() = SubscriptionRegistry()

    /**
     * Cached inside the resolver rather than here, and shared as one bean: the cache is the
     * point (#114), and a second instance would refetch every problem the first already
     * knows.
     */
    @Bean
    fun problemIdentityResolver(pages: PageSource): ProblemIdentityResolver = PageProblemIdentityResolver(pages = pages)

    /**
     * One bean, because the cache is the point. A second instance would probe Programmers on
     * its own schedule and halve the interval that keeps the check affordable (#179).
     */
    @Bean
    fun sessionHealth(pages: PageSource, clock: Clock): SessionHealth =
        SessionHealth(SessionActivityProbe(pages), clock)

    @Bean
    fun watchRequestHandler(
        registry: SubscriptionRegistry,
        subscriber: ChannelSubscriber,
        timer: ProblemTimer,
        identities: ProblemIdentityResolver,
        sessions: SessionHealth,
        clock: Clock,
    ): WatchRequestHandler = WatchService(registry, subscriber, timer, identities, sessions, clock)
}
