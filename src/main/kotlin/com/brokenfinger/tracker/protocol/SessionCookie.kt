package com.brokenfinger.tracker.protocol

/**
 * The Programmers `_session_production` cookie header value, kept in memory only.
 *
 * The raw value is reachable solely through [headerValue]; `toString` is masked so the
 * cookie can never leak into logs or exception messages at any level (dev rules §7.2).
 */
@JvmInline
value class SessionCookie(private val raw: String) {
    init {
        require(raw.isNotBlank()) { "session cookie must not be blank" }
    }

    fun headerValue(): String = raw

    override fun toString(): String = "SessionCookie(***)"
}
