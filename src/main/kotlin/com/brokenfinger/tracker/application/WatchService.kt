package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.protocol.ChallengeableId
import com.brokenfinger.tracker.protocol.ChallengeableType
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.LessonId
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
    private val clock: Clock = Clock.systemUTC(),
) : WatchRequestHandler {
    override fun watch(command: WatchCommand): WatchOutcome {
        val identifier = identifierOf(command)
        return outcomeOf(identifier, registry.watch(identifier, clock.instant()))
    }

    private fun outcomeOf(identifier: ChannelIdentifier, result: WatchResult): WatchOutcome = when (result) {
        is WatchResult.AlreadyWatching -> WatchOutcome.REFRESHED
        is WatchResult.Started -> started(identifier, result.evicted)
        is WatchResult.Saturated -> throw WatchCapacityExceededException(SATURATED)
    }

    private fun started(identifier: ChannelIdentifier, evicted: ChannelIdentifier?): WatchOutcome {
        evicted?.let(subscriber::unsubscribe)
        subscriber.subscribe(identifier)
        return WatchOutcome.STARTED
    }

    private fun identifierOf(command: WatchCommand) = ChannelIdentifier.of(
        type = typeOf(command.kind),
        lessonId = LessonId(command.lessonId),
        challengeableId = ChallengeableId(command.challengeableId),
        language = command.language,
    )

    private fun typeOf(kind: ProblemKind) = when (kind) {
        ProblemKind.ALGORITHM -> ChallengeableType.ALGORITHM
        ProblemKind.DATABASE -> ChallengeableType.DATABASE
    }

    private companion object {
        const val SATURATED = "every subscription slot is held by a live grading"
    }
}
