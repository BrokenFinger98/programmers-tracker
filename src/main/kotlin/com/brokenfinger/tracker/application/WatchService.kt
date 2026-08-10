package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.LessonId
import java.time.Clock

/**
 * Owns the [SubscriptionRegistry] and turns its decisions into subscription changes.
 *
 * The registry decides *what* should be held; this decides *when the socket changes*. An
 * eviction unsubscribes before the newcomer subscribes, so the cap is never exceeded even
 * briefly, and a repeat watch touches nothing but recency — the extension re-posts every
 * 30 s per open tab, and re-subscribing on each heartbeat would duplicate records.
 */
class WatchService(
    private val registry: SubscriptionRegistry,
    private val subscriber: ChannelSubscriber,
    private val timer: ProblemTimer,
    private val identities: ProblemIdentityResolver,
    private val clock: Clock = Clock.systemUTC(),
) : WatchRequestHandler {
    /**
     * Resolving before starting the clock is deliberate: a request we cannot turn into a
     * channel never happened, and starting a timer for it would put a measured-looking
     * elapsed time on a problem nobody is watching.
     */
    override suspend fun watch(command: WatchCommand): WatchOutcome {
        val channel = channelOf(command) ?: throw UnresolvableProblemException(command.lessonId)
        // The sensor's first report is the only moment we learn a problem was opened, so it
        // is the only place the clock can start. Without this every record carries
        // elapsedSec 0 — a measured-looking zero, which is worse than an absent value.
        timer.startIfAbsent(command.lessonId)
        return outcomeOf(channel, registry.watch(channel, clock.instant()))
    }

    private fun outcomeOf(channel: ChannelKey, result: WatchResult): WatchOutcome = when (result) {
        is WatchResult.AlreadyWatching -> WatchOutcome.REFRESHED
        is WatchResult.Started -> started(channel, result.evicted)
        is WatchResult.Saturated -> throw WatchCapacityExceededException(SATURATED)
    }

    private fun started(channel: ChannelKey, evicted: ChannelKey?): WatchOutcome {
        evicted?.let(subscriber::unsubscribe)
        subscriber.subscribe(channel)
        return WatchOutcome.STARTED
    }

    private suspend fun channelOf(command: WatchCommand): ChannelKey? {
        val lessonId = LessonId(command.lessonId)
        val resolved = identities.resolve(lessonId, command.language) ?: return null
        return ChannelKey.of(
            lessonId = lessonId,
            challengeableId = resolved.challengeableId,
            kind = resolved.kind,
            language = command.language,
        )
    }

    private companion object {
        const val SATURATED = "every subscription slot is held by a live grading"
    }
}
