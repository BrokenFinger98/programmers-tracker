package com.brokenfinger.tracker.application

import java.time.Duration

/**
 * The silence-and-backoff policy for the observation socket
 * ([[decisions/2026-08-05-failure-taxonomy]] decision 4) — the numbers, and the reasoning
 * that fixes them.
 *
 * `{"type":"ping"}` arrives every 3 seconds (protocol §4), which makes silence the one
 * liveness signal we get for free. It is not a theoretical state: during the #10 upgrade
 * verification an **idle observation socket closed silently after ~30 m 50 s** — no
 * exception, no close frame, the frame flow simply completed (2026-08-05). Everything
 * Programmers broadcast after that moment is unrecoverable (protocol §11), and nothing in
 * the logs said so.
 *
 * **Numbers only, deliberately.** This used to also carry a `frameArrived` / `check` /
 * `isDead` API that tracked the last frame itself. Nothing ever called it: the one production
 * consumer, `CableChannelSubscriber`, expresses the same rule as a Flow `timeout` and takes
 * only the constants from here. Two spellings of one rule is the drift the pure calculators
 * exist to prevent (dev rules §3), and the spelling that does not run is the one that misleads
 * — someone adding a second observation path would wire it to `isDead()` believing they had
 * adopted the shipped policy. Removed in #49; a caller that genuinely needs to ask "is it dead
 * right now" should add it back with a consumer in the same change.
 */
object ConnectionLiveness {
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
     * Overridable at the call site so a deployment on a worse network is not stuck with our
     * number.
     */
    val DEFAULT_DEADLINE: Duration = PING_INTERVAL.multipliedBy(5)

    /** The ceiling every later attempt reuses — a quarter of the 120 s grading timeout. */
    val BACKOFF_CAP: Duration = Duration.ofSeconds(30)

    /**
     * Doubling from 1 s up to the cap, then flat forever: bounded by construction, so a long
     * outage can never push the next attempt beyond a grading window. No jitter — there is
     * exactly one client per user, so there is no herd to spread out.
     */
    val BACKOFF_SCHEDULE: List<Duration> =
        listOf(1L, 2L, 4L, 8L, 16L).map(Duration::ofSeconds) + BACKOFF_CAP

    /** How long to wait before reconnect attempt [attempt], numbered from 1. */
    fun retryDelayFor(attempt: Int): Duration {
        require(attempt >= 1) { "reconnect attempts are numbered from 1: $attempt" }
        return BACKOFF_SCHEDULE.getOrElse(attempt - 1) { BACKOFF_CAP }
    }
}
