package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChallengeableId
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
    private val clock: Clock = Clock.systemUTC(),
) : WatchRequestHandler {
    override fun watch(command: WatchCommand): WatchOutcome {
        // The extension's first report is the only moment we learn a problem was opened, so
        // it is the only place the clock can start. Without this every record carries
        // elapsedSec 0 — a measured-looking zero, which is worse than an absent value.
        timer.startIfAbsent(command.lessonId)
        val channel = channelOf(command)
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

    private fun channelOf(command: WatchCommand) = ChannelKey.of(
        lessonId = LessonId(command.lessonId),
        challengeableId = ChallengeableId(command.challengeableId),
        kind = command.kind,
        language = command.language,
    )

    private companion object {
        const val SATURATED = "every subscription slot is held by a live grading"
    }
}
