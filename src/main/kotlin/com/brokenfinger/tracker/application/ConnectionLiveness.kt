package com.brokenfinger.tracker.application

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** What the liveness policy says about the connection right now, with the data a log line needs. */
sealed interface Liveness {
    /** No frame has ever arrived. A connection that has not started is not a dead one. */
    data object NotStarted : Liveness

    data class Alive(val lastFrameAt: Instant, val silence: Duration) : Liveness

    data class Dead(val lastFrameAt: Instant, val silence: Duration) : Liveness
}

/**
 * The dead-connection policy for the observation socket
 * ([[decisions/2026-08-05-failure-taxonomy.md]] decision 4).
 *
 * `{"type":"ping"}` arrives every 3 seconds (protocol §4), which makes silence the one
 * liveness signal we get for free. It is not a theoretical state: during the #10 upgrade
 * verification an **idle observation socket closed silently after ~30 m 50 s** — no
 * exception, no close frame, the frame flow simply completed (2026-08-05). Everything
 * Programmers broadcast after that moment is unrecoverable (protocol §11), and nothing in
 * the logs said so. Detecting the gap and saying it loudly is the whole point of this class.
 *
 * Pure policy: it is told that a frame arrived and answers questions. It owns no socket, no
 * coroutine and no timer — the caller does the watching, the reconnecting and the waiting.
 */
class ConnectionLiveness(private val clock: Clock, private val deadline: Duration = DEFAULT_DEADLINE) {
    // Frames arrive on the socket's coroutine while a watchdog asks from another thread.
    private val lastFrameAt = AtomicReference<Instant>()

    init {
        require(!deadline.isZero && !deadline.isNegative) { "deadline must be positive: $deadline" }
    }

    /** Any frame counts, not only a ping — a broadcast is just as good a proof of life. */
    fun frameArrived() = lastFrameAt.set(clock.instant())

    /** The current verdict, carrying the gap window a caller logs verbatim. */
    fun check(): Liveness {
        val last = lastFrameAt.get() ?: return Liveness.NotStarted
        val silence = Duration.between(last, clock.instant())
        if (silence > deadline) return Liveness.Dead(last, silence)
        return Liveness.Alive(last, silence)
    }

    fun isDead(): Boolean = check() is Liveness.Dead

    /**
     * How long to wait before reconnect attempt [attempt], numbered from 1. Pure data — the
     * caller performs the waiting, so this stays testable without sleeping.
     */
    fun retryDelay(attempt: Int): Duration {
        require(attempt >= 1) { "reconnect attempts are numbered from 1: $attempt" }
        return BACKOFF_SCHEDULE.getOrElse(attempt - 1) { BACKOFF_CAP }
    }

    companion object {
        /** The schedule as a plain function, for callers that hold no instance. */
        fun retryDelayFor(attempt: Int): Duration {
            require(attempt >= 1) { "reconnect attempts are numbered from 1: $attempt" }
            return BACKOFF_SCHEDULE.getOrElse(attempt - 1) { BACKOFF_CAP }
        }

        /** Measured: the server heartbeat arrives every 3 seconds (protocol §4). */
        val PING_INTERVAL: Duration = Duration.ofSeconds(3)

        /**
         * Five ping intervals — 15 s. The multiple is bounded on both sides:
         *
         * - **Not lower**, because one or two missed pings are ordinary jitter (a GC pause, a
         *   Wi-Fi hiccup) and reconnecting on those would drop a live subscription for nothing.
         *   Four consecutive misses are not jitter.
         * - **Not higher**, because a grading run measured 87 s and times out at 120 s. The
         *   detection window plus a full backoff must fit inside a grading, or the reconnect
         *   finishes after the result was already broadcast — and it is never re-sent.
         *
         * Overridable so a deployment on a worse network is not stuck with our number.
         */
        val DEFAULT_DEADLINE: Duration = PING_INTERVAL.multipliedBy(5)

        /** The ceiling every later attempt reuses — a quarter of the 120 s grading timeout. */
        val BACKOFF_CAP: Duration = Duration.ofSeconds(30)

        /**
         * Doubling from 1 s up to the cap, then flat forever: bounded by construction, so a
         * long outage can never push the next attempt beyond a grading window. No jitter —
         * there is exactly one client per user, so there is no herd to spread out.
         */
        val BACKOFF_SCHEDULE: List<Duration> =
            listOf(1L, 2L, 4L, 8L, 16L).map(Duration::ofSeconds) + BACKOFF_CAP
    }
}
