package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What a `/watch` did, so the caller knows whether to subscribe, unsubscribe, or fail. */
sealed interface WatchResult {
    /**
     * A new subscription opened. [evicted] — when present — was dropped to make room and the
     * caller must unsubscribe it on the socket; the registry only owns the bookkeeping.
     */
    data class Started(val evicted: ChannelKey?) : WatchResult

    /** Already subscribed; recency refreshed and nothing else done (design §4.1 idempotence). */
    data object AlreadyWatching : WatchResult

    /** Every slot holds a live grading session. The caller fails loudly, never silently. */
    data object Saturated : WatchResult
}

/** One watched channel as the diagnostics view sees it. */
data class WatchedChannel(val channel: ChannelKey, val lastHeartbeat: Instant, val pinned: Boolean) {
    internal fun heartbeatAt(now: Instant) = copy(lastHeartbeat = now)

    internal fun activated() = copy(pinned = true)

    internal fun settled() = copy(pinned = false)
}

/**
 * The bounded set of channels we are subscribed to
 * ([[decisions/2026-08-05-failure-taxonomy.md]] decision 5, design §4.1).
 *
 * Three rules, all of them measured consequences rather than preferences:
 *
 * - **Idempotent.** The extension re-`POST`s a heartbeat every 30 s for every open tab, so a
 *   repeat is the normal case, not an error. It refreshes recency and does nothing else —
 *   re-subscribing would duplicate records.
 * - **Eviction orders by last heartbeat**, which is what makes "oldest" mean anything against
 *   that heartbeat: the least recently touched tab goes first.
 * - **A live grading session is pinned** and is skipped no matter how old it is; evicting
 *   mid-grading loses a result permanently (protocol §11). When every slot is pinned we return
 *   [WatchResult.Saturated] rather than declining to observe in silence.
 *
 * No clock inside: `now` arrives with the heartbeat that caused the call, which keeps the
 * eviction order testable and identical on the reconnect replay path.
 *
 * Sized for one writer (the `/watch` handler) plus concurrent readers (diagnostics); entries
 * are immutable and replaced wholesale, so a reader never observes a half-updated one.
 */
class SubscriptionRegistry(private val capacity: Int = DEFAULT_CAPACITY) {
    private val watched = ConcurrentHashMap<ChannelKey, WatchedChannel>()

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    /** Opens a subscription, or refreshes one we already hold. */
    fun watch(channel: ChannelKey, now: Instant): WatchResult {
        val existing = watched[channel]
        if (existing != null) return refresh(existing, now)
        if (watched.size < capacity) return admit(channel, now, evicted = null)
        val victim = evictionVictim() ?: return WatchResult.Saturated
        watched.remove(victim.channel)
        return admit(channel, now, victim.channel)
    }

    /** Pins the channel against eviction — a grading session is running on it. */
    fun markActive(channel: ChannelKey) = replace(channel) { it.activated() }

    /** Releases the pin once the session reached a terminal frame or its timeout. */
    fun markSettled(channel: ChannelKey) = replace(channel) { it.settled() }

    /** Drops the subscription; `false` means there was nothing to drop. */
    fun unwatch(channel: ChannelKey): Boolean = watched.remove(channel) != null

    /** Diagnostics view, oldest heartbeat first — the order eviction would follow. */
    fun snapshot(): List<WatchedChannel> = watched.values.sortedBy { it.lastHeartbeat }

    private fun refresh(existing: WatchedChannel, now: Instant): WatchResult {
        watched[existing.channel] = existing.heartbeatAt(now)
        return WatchResult.AlreadyWatching
    }

    private fun admit(channel: ChannelKey, now: Instant, evicted: ChannelKey?): WatchResult {
        watched[channel] = WatchedChannel(channel, now, pinned = false)
        return WatchResult.Started(evicted)
    }

    /** Oldest heartbeat among the unpinned; `null` when every slot is grading. */
    private fun evictionVictim(): WatchedChannel? =
        watched.values.filterNot { it.pinned }.minByOrNull { it.lastHeartbeat }

    private fun replace(channel: ChannelKey, change: (WatchedChannel) -> WatchedChannel) {
        val current = watched[channel]
            ?: error("Not watching lesson ${channel.lessonId.value}: registry out of sync with the socket")
        watched[channel] = change(current)
    }

    companion object {
        /** Design §4.1. Overridable so a deployment is never stuck with our number. */
        const val DEFAULT_CAPACITY = 8
    }
}
