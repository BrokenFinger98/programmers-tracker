package com.brokenfinger.tracker.domain

/**
 * Whether the Programmers session cookie still authenticates.
 *
 * This exists because the socket cannot answer it. An unauthenticated subscription is confirmed
 * in half a second, pinged normally, and receives no broadcasts at all (protocol doc §15.3), so
 * every liveness signal a passive observer has is identical between a working session and a dead
 * one. The answer has to come from an authenticated HTTP request (#179).
 */
enum class SessionState {
    /** The measured endpoint answered 200. */
    ALIVE,

    /** It answered 401 — the cookie no longer authenticates and nothing will be recorded. */
    EXPIRED,

    /**
     * Anything else: a 5xx, a throttle, a network failure, a body that did not parse.
     *
     * **Never folded into [EXPIRED].** Telling someone to replace a credential that is fine is a
     * way to teach them to ignore the message, and this project has already shipped one
     * confidently wrong health answer (#175).
     */
    UNKNOWN,
    ;

    /** Whether a grading would be delivered to us at all if one happened now. */
    fun authenticated(): Boolean = this != EXPIRED
}
