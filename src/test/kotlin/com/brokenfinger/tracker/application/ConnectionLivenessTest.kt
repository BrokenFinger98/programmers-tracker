package com.brokenfinger.tracker.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ConnectionLivenessTest {
    private val clock = MovableClock()
    private val liveness = ConnectionLiveness(clock)

    @Test
    fun `a connection that has never seen a frame is not dead — it has not started`() {
        // Reporting dead here would make every process launch begin with a reconnect storm.
        clock.advance(Duration.ofMinutes(10))

        liveness.check() shouldBe Liveness.NotStarted
        liveness.isDead() shouldBe false
    }

    @Test
    fun `silence shorter than the deadline is alive`() {
        liveness.frameArrived()

        clock.advance(Duration.ofSeconds(9))

        liveness.isDead() shouldBe false
    }

    @Test
    fun `silence of exactly the deadline is still alive — the deadline is the last tolerated gap`() {
        liveness.frameArrived()

        clock.advance(ConnectionLiveness.DEFAULT_DEADLINE)

        liveness.isDead() shouldBe false
    }

    @Test
    fun `silence past the deadline is dead`() {
        liveness.frameArrived()

        clock.advance(ConnectionLiveness.DEFAULT_DEADLINE.plusMillis(1))

        liveness.isDead() shouldBe true
    }

    @Test
    fun `a dead connection reports when the last frame arrived and how long the silence has lasted`() {
        val lastFrame = clock.instant()
        liveness.frameArrived()

        clock.advance(Duration.ofMinutes(31))
        val dead = liveness.check().shouldBeInstanceOf<Liveness.Dead>()

        dead.lastFrameAt shouldBe lastFrame
        dead.silence shouldBe Duration.ofMinutes(31)
    }

    @Test
    fun `an alive connection reports the same gap data, so a caller can log it without a second clock`() {
        val lastFrame = clock.instant()
        liveness.frameArrived()

        clock.advance(Duration.ofSeconds(4))
        val alive = liveness.check().shouldBeInstanceOf<Liveness.Alive>()

        alive.lastFrameAt shouldBe lastFrame
        alive.silence shouldBe Duration.ofSeconds(4)
    }

    @Test
    fun `any frame resets the silence, not only a ping`() {
        liveness.frameArrived()
        clock.advance(Duration.ofMinutes(5))
        liveness.isDead() shouldBe true

        liveness.frameArrived()

        liveness.isDead() shouldBe false
        liveness.check().shouldBeInstanceOf<Liveness.Alive>().silence shouldBe Duration.ZERO
    }

    @Test
    fun `the deadline is configurable — a deployment is never stuck with our number`() {
        val impatient = ConnectionLiveness(clock, deadline = Duration.ofSeconds(6))
        impatient.frameArrived()

        clock.advance(Duration.ofSeconds(7))

        impatient.isDead() shouldBe true
    }

    @Test
    fun `a non-positive deadline is rejected — it would call every connection dead at once`() {
        shouldThrow<IllegalArgumentException> { ConnectionLiveness(clock, deadline = Duration.ZERO) }
    }

    @Test
    fun `the default deadline is a bounded multiple of the measured 3 s ping cadence`() {
        ConnectionLiveness.DEFAULT_DEADLINE shouldBe ConnectionLiveness.PING_INTERVAL.multipliedBy(5)
    }

    @Test
    fun `the backoff schedule doubles from one second and stops at the cap`() {
        val delays = (1..6).map { liveness.retryDelay(it) }

        delays shouldBe listOf(1L, 2L, 4L, 8L, 16L, 30L).map(Duration::ofSeconds)
    }

    @Test
    fun `backoff never grows past the cap, however long the outage lasts`() {
        liveness.retryDelay(7) shouldBe ConnectionLiveness.BACKOFF_CAP
        liveness.retryDelay(1_000) shouldBe ConnectionLiveness.BACKOFF_CAP
    }

    @Test
    fun `the backoff cap stays well inside a grading window, so a reconnect can still catch the result`() {
        // A grading run measured 87 s and times out at 120 s (protocol §13); a retry delay
        // longer than a quarter of that would lose the broadcast it exists to catch.
        (ConnectionLiveness.BACKOFF_CAP <= Duration.ofSeconds(30)) shouldBe true
    }

    @Test
    fun `the first retry is not counted from zero — attempt numbering starts at one`() {
        shouldThrow<IllegalArgumentException> { liveness.retryDelay(0) }
    }

    /** A clock the test moves by hand; liveness is about elapsed time, never about sleeping. */
    private class MovableClock : Clock() {
        private var current = Instant.parse("2026-08-05T00:00:00Z")

        fun advance(amount: Duration) {
            current = current.plus(amount)
        }

        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
