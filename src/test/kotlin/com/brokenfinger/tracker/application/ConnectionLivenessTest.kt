package com.brokenfinger.tracker.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The silence-and-backoff numbers, and the reasoning that fixes them.
 *
 * Every assertion here is really about a **bound** rather than a value: each number sits
 * between two failures, and a test that only pinned the literal would let someone change it
 * to a number that still passes and still loses graded results. So the comparisons are
 * written against the measurements that constrain them — the 3 s ping cadence and the 120 s
 * grading timeout — rather than against the constants themselves.
 *
 * The `frameArrived` / `check` / `isDead` API these tests used to cover was removed in #49:
 * nothing called it, and the one production consumer expresses the same rule as a Flow
 * timeout. Tests for an API nobody uses report coverage of a decision that is not in force.
 */
class ConnectionLivenessTest {
    @Test
    fun `the ping cadence is the measured one, because every other number derives from it`() {
        ConnectionLiveness.PING_INTERVAL shouldBe Duration.ofSeconds(3)
    }

    @Test
    fun `the default deadline is a bounded multiple of the measured 3 s ping cadence`() {
        ConnectionLiveness.DEFAULT_DEADLINE shouldBe ConnectionLiveness.PING_INTERVAL.multipliedBy(5)
    }

    /**
     * Not lower: one or two missed pings are ordinary jitter — a GC pause, a Wi-Fi hiccup —
     * and reconnecting on those drops a live subscription for nothing.
     */
    @Test
    fun `the deadline tolerates more than two missed pings`() {
        (ConnectionLiveness.DEFAULT_DEADLINE > ConnectionLiveness.PING_INTERVAL.multipliedBy(2)) shouldBe true
    }

    /**
     * Not higher: a grading run measured 87 s and times out at 120 s (protocol §13). Detection
     * plus a full backoff has to fit inside a grading, or the reconnect completes after the
     * result was broadcast — and it is never re-sent.
     */
    @Test
    fun `detection plus a full backoff fits inside a grading window`() {
        val worstCase = ConnectionLiveness.DEFAULT_DEADLINE + ConnectionLiveness.BACKOFF_CAP

        (worstCase < GRADING_TIMEOUT) shouldBe true
    }

    @Test
    fun `the backoff schedule doubles from one second and stops at the cap`() {
        val delays = (1..6).map(ConnectionLiveness::retryDelayFor)

        delays shouldBe listOf(1L, 2L, 4L, 8L, 16L, 30L).map(Duration::ofSeconds)
    }

    @Test
    fun `backoff never grows past the cap, however long the outage lasts`() {
        ConnectionLiveness.retryDelayFor(7) shouldBe ConnectionLiveness.BACKOFF_CAP
        ConnectionLiveness.retryDelayFor(1_000) shouldBe ConnectionLiveness.BACKOFF_CAP
    }

    @Test
    fun `the backoff cap stays well inside a grading window, so a reconnect can still catch the result`() {
        (ConnectionLiveness.BACKOFF_CAP <= GRADING_TIMEOUT.dividedBy(4)) shouldBe true
    }

    @Test
    fun `the first retry is not counted from zero — attempt numbering starts at one`() {
        shouldThrow<IllegalArgumentException> { ConnectionLiveness.retryDelayFor(0) }
    }

    private companion object {
        /** Measured: a grading times out at 120 s, and one run took 87 s (protocol §13). */
        val GRADING_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
