package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.SubscriptionHealth

/**
 * What a heartbeat's look at the credential found (#331).
 *
 * Two answers, because they drive two different things: [changed] — the credential is not the
 * one the last heartbeat saw — is what makes a cached session answer stale, whether or not any
 * socket had to move; [reopened] says this channel's observation was closed and started again
 * under the current one. A socket that reconnected on its own between the replacement and the
 * heartbeat is `changed` and not `reopened`, and the cache must still be forgotten.
 */
data class CredentialCheck(val changed: Boolean, val reopened: Boolean) {
    companion object {
        val UNCHANGED = CredentialCheck(changed = false, reopened = false)
    }
}

/**
 * Outbound port for holding a channel subscription open. Separated from [WatchRequestHandler]
 * so the registry's bookkeeping is testable without a socket, and so eviction has somewhere
 * to send its "stop listening to this one" (design §4.1).
 *
 * Named by [ChannelKey], not by the wire identifier: which channel to hold open is an
 * identity question, and how it is spelled on the socket is the adapter's business
 * ([[decisions/2026-08-05-protocol-dependency-direction]]).
 */
interface ChannelSubscriber {
    fun subscribe(channel: ChannelKey)

    fun unsubscribe(channel: ChannelKey)

    /**
     * Whether that subscription is actually observing right now.
     *
     * Subscribing is fire-and-forget by design — the socket outlives the request that asked
     * for it — so the only honest way to answer "is this being watched" is to ask afterwards
     * (#167). A channel this subscriber holds nothing for answers
     * [SubscriptionHealth.UNREACHABLE]: the optimistic default is the bug.
     */
    fun healthOf(channel: ChannelKey): SubscriptionHealth

    /**
     * Reopens the observation of [channel] if the credential it authenticated with is no longer
     * the current one, and says whether it did.
     *
     * A socket accepted with a dead cookie stays confirmed, pinged and empty for as long as it
     * lives, and nothing on it says so ([[sources/2026-08-11-expiry-has-no-socket-signal]]). So
     * when the credential is replaced the observation has to be replaced with it, and the only
     * caller regular enough to notice is the heartbeat (#331). A channel this subscriber holds
     * nothing for has nothing to reopen; whether the credential changed is answered regardless.
     *
     * Suspends because the old observation is closed *before* the new one opens: two collectors
     * on one channel would break [ChannelCapture]'s one-collector rule.
     *
     * [mayReopen] false answers whether the credential changed but leaves the socket alone: a
     * grading in flight on it is worth more than a fresher cookie, and the next heartbeat after
     * it settles will reopen (#332).
     */
    suspend fun reauthenticate(channel: ChannelKey, mayReopen: Boolean = true): CredentialCheck
}
