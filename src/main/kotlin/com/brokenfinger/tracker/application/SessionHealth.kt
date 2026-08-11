package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SessionState

/**
 * Outbound port for "does the session cookie still authenticate".
 *
 * A port rather than a direct call because the answer costs a request to Programmers, and
 * everything above it has to be testable without one (dev rules §1).
 */
fun interface SessionProbe {
    suspend fun probe(): SessionState
}

/**
 * The session's state, asked at most once per [interval].
 *
 * The extension posts `/watch` every 30 seconds **per open tab**, so probing on each one would
 * mean a request to Programmers every few seconds for as long as a tab is open — well past what
 * development-rules §9.3 calls the same level as a browser. The cache is what makes the check
 * affordable enough to be worth having at all.
 *
 * **The cached answer is never `UNKNOWN`.** A failed probe is not remembered: the next heartbeat
 * asks again, because an unreachable check that stuck for five minutes would report nothing
 * useful for five minutes.
 */
class SessionHealth(
    private val probe: SessionProbe,
    private val clock: java.time.Clock,
    private val interval: java.time.Duration = DEFAULT_INTERVAL,
) {
    private val lock = Any()
    private var answer: SessionState = SessionState.UNKNOWN
    private var askedAt: java.time.Instant? = null

    suspend fun state(): SessionState {
        synchronized(lock) { cached() }?.let { return it }
        val fresh = probe.probe()
        synchronized(lock) {
            answer = fresh
            askedAt = if (fresh == SessionState.UNKNOWN) null else clock.instant()
        }
        return fresh
    }

    private fun cached(): SessionState? {
        val at = askedAt ?: return null
        if (java.time.Duration.between(at, clock.instant()) >= interval) return null
        return answer
    }

    companion object {
        /**
         * Five minutes. Chosen, not measured: long enough that a tab open all evening costs ~12
         * requests an hour, short enough that a cookie dying mid-session is noticed inside one
         * problem rather than after it.
         */
        val DEFAULT_INTERVAL: java.time.Duration = java.time.Duration.ofMinutes(5)
    }
}
