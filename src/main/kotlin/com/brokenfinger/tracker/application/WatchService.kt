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
    private val sessions: SessionHealth,
    private val clock: Clock = Clock.systemUTC(),
) : WatchRequestHandler {
    /**
     * Resolving before starting the clock is deliberate: a request we cannot turn into a
     * channel never happened, and starting a timer for it would put a measured-looking
     * elapsed time on a problem nobody is watching.
     */
    override suspend fun watch(command: WatchCommand): WatchStatus {
        val channel = channelOf(command) ?: throw UnresolvableProblemException(command.lessonId)
        // The sensor's first report is the only moment we learn a problem was opened, so it
        // is the only place the clock can start. Without this every record carries
        // elapsedSec 0 — a measured-looking zero, which is worse than an absent value.
        timer.startIfAbsent(command.lessonId)
        // After the start, so a first heartbeat carrying an observation still lands: the
        // timer refuses a reading for a problem it has no clock for.
        command.observation?.let { timer.observed(command.lessonId, it) }
        val outcome = outcomeOf(channel, registry.watch(channel, clock.instant()))
        // Asked after the subscription rather than assumed from it: whether the socket lives
        // is not something this call can promise (#167).
        //
        // Two independent answers, because a healthy socket does not imply a valid session —
        // an unauthenticated subscription is confirmed and pinged and delivers nothing (#179).
        val session = sessions.state()
        // The heartbeat is the only thing that asks, so it is where a check that has gone
        // quiet gets noticed (#189).
        sessions.muteChanged()?.let(::reportMute)
        return WatchStatus(outcome, subscriber.healthOf(channel), session)
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

    /**
     * Warned rather than surfaced: the badge vocabulary is full
     * ([[decisions/2026-08-11-a-watch-answer-is-not-a-promise]]), and "we cannot tell whether
     * you are recording" is a diagnostic for whoever reads logs rather than something to put in
     * front of someone mid-problem.
     */
    private fun reportMute(mute: Boolean) {
        if (mute) {
            logger.warn(
                "The session check has been unable to answer for over {} minutes. Expired-cookie " +
                    "detection is not working — the endpoint it rests on is one Programmers never " +
                    "promised us, so this may be a protocol change (protocol doc §15.4).",
                SessionHealth.MUTE_AFTER.toMinutes(),
            )
            return
        }
        logger.info("The session check can answer again.")
    }

    private companion object {
        val logger = org.slf4j.LoggerFactory.getLogger(WatchService::class.java)
        const val SATURATED = "every subscription slot is held by a live grading"
    }
}
