package com.brokenfinger.tracker.domain

/**
 * Whether a watched channel is actually being observed right now.
 *
 * `/watch` used to answer `started` unconditionally, because subscribing is fire-and-forget:
 * the request returned 200 whether the socket confirmed, was refused, or never opened (#167,
 * found as M1 on 2026-08-07). The user saw a green badge and kept solving while every grading
 * was lost. This is the value that makes the answer conditional.
 *
 * The two failing states are kept apart because they ask the user for different things —
 * [REJECTED] means paste a fresh session cookie, [UNREACHABLE] means wait. Collapsing them
 * into one "not working" would be honest and useless.
 */
enum class SubscriptionHealth {
    /** Subscribed; nothing has come back yet. The judge pings every ~3 s, so this is brief. */
    PENDING,

    /** A frame arrived on the current attempt. */
    LIVE,

    /**
     * The judge refused the subscription. **Retrying with the same cookie cannot fix it**,
     * which is what makes it worth telling the user about rather than logging.
     */
    REJECTED,

    /** The socket keeps failing for some other reason; the retry loop is still trying. */
    UNREACHABLE,
    ;

    /**
     * Whether a grading on this channel would be captured if it happened now.
     *
     * [PENDING] counts: a subscription a moment old is not a fault, and alarming on it would
     * make the first heartbeat of every problem look broken.
     */
    fun observing(): Boolean = this == PENDING || this == LIVE
}
