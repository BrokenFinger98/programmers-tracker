package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.protocol.ChannelIdentifier
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What a `/watch` did, so the caller knows whether to subscribe, unsubscribe, or fail. */
sealed interface WatchResult {
    /**
     * A new subscription opened. [evicted] — when present — was dropped to make room and the
     * caller must unsubscribe it on the socket; the registry only owns the bookkeeping.
     */
    data class Started(val evicted: ChannelIdentifier?) : WatchResult

    /** Already subscribed; recency refreshed and nothing else done (design §4.1 idempotence). */
    data object AlreadyWatching : WatchResult

    /** Every slot holds a live grading session. The caller fails loudly, never silently. */
    data object Saturated : WatchResult
}

/** One watched channel as the diagnostics view sees it. */
data class WatchedChannel(val identifier: ChannelIdentifier, val lastHeartbeat: Instant, val pinned: Boolean) {
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
    private val watched = ConcurrentHashMap<ChannelIdentifier, WatchedChannel>()

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    /** Opens a subscription, or refreshes one we already hold. */
    fun watch(identifier: ChannelIdentifier, now: Instant): WatchResult {
        val existing = watched[identifier]
        if (existing != null) return refresh(existing, now)
        if (watched.size < capacity) return admit(identifier, now, evicted = null)
        val victim = evictionVictim() ?: return WatchResult.Saturated
        watched.remove(victim.identifier)
        return admit(identifier, now, victim.identifier)
    }

    /** Pins the channel against eviction — a grading session is running on it. */
    fun markActive(identifier: ChannelIdentifier) = replace(identifier) { it.activated() }

    /** Releases the pin once the session reached a terminal frame or its timeout. */
    fun markSettled(identifier: ChannelIdentifier) = replace(identifier) { it.settled() }

    /** Drops the subscription; `false` means there was nothing to drop. */
    fun unwatch(identifier: ChannelIdentifier): Boolean = watched.remove(identifier) != null

    /** Diagnostics view, oldest heartbeat first — the order eviction would follow. */
    fun snapshot(): List<WatchedChannel> = watched.values.sortedBy { it.lastHeartbeat }

    private fun refresh(existing: WatchedChannel, now: Instant): WatchResult {
        watched[existing.identifier] = existing.heartbeatAt(now)
        return WatchResult.AlreadyWatching
    }

    private fun admit(identifier: ChannelIdentifier, now: Instant, evicted: ChannelIdentifier?): WatchResult {
        watched[identifier] = WatchedChannel(identifier, now, pinned = false)
        return WatchResult.Started(evicted)
    }

    /** Oldest heartbeat among the unpinned; `null` when every slot is grading. */
    private fun evictionVictim(): WatchedChannel? =
        watched.values.filterNot { it.pinned }.minByOrNull { it.lastHeartbeat }

    private fun replace(identifier: ChannelIdentifier, change: (WatchedChannel) -> WatchedChannel) {
        val current = watched[identifier]
            ?: error("Not watching lesson ${identifier.lessonId.value}: registry out of sync with the socket")
        watched[identifier] = change(current)
    }

    companion object {
        /** Design §4.1. Overridable so a deployment is never stuck with our number. */
        const val DEFAULT_CAPACITY = 8
    }
}
