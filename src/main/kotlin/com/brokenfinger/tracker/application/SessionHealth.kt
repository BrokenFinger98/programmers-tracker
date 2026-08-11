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
    private var unansweredSince: java.time.Instant? = null
    private var reportedMute = false

    suspend fun state(): SessionState {
        synchronized(lock) { cached() }?.let { return it }
        val fresh = probe.probe()
        synchronized(lock) {
            answer = fresh
            askedAt = if (fresh == SessionState.UNKNOWN) null else clock.instant()
            track(fresh)
        }
        return fresh
    }

    /**
     * Whether the check has stopped being able to answer, or has started again — reported once
     * each, never per probe.
     *
     * The endpoint this rests on is one Programmers never promised us. If it moves or starts
     * answering 403, every probe returns `UNKNOWN`, the badge stays quiet by design, and the
     * expired-cookie detection is simply gone with nothing saying so (#189). The project already
     * has this rule for messages — an unrecognised type is kept **and warned about**, because
     * that is the only way to notice a protocol change; the session check had the first half.
     *
     * A blip is not news. Only a run of failures longer than [MUTE_AFTER] is, and so is its end.
     */
    fun muteChanged(): Boolean? = synchronized(lock) {
        val mute = isMute()
        if (mute == reportedMute) return null
        reportedMute = mute
        return mute
    }

    private fun isMute(): Boolean {
        val since = unansweredSince ?: return false
        return java.time.Duration.between(since, clock.instant()) >= MUTE_AFTER
    }

    // Called inside the lock. A definite answer of either kind ends the run — EXPIRED is the
    // check working, not failing.
    private fun track(fresh: SessionState) {
        if (fresh != SessionState.UNKNOWN) {
            unansweredSince = null
            return
        }
        if (unansweredSince == null) unansweredSince = clock.instant()
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

        /**
         * Half an hour of asking and never getting an answer. Chosen, not measured: long enough
         * that a laptop losing wifi mid-problem stays silent, short enough that a protocol change
         * is noticed the same session it happens.
         */
        val MUTE_AFTER: java.time.Duration = java.time.Duration.ofMinutes(30)
    }
}
