package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SessionState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cache is what makes the check affordable: the extension posts `/watch` every 30 seconds
 * **per open tab**, so probing on each one would mean a request to Programmers every few seconds
 * for as long as a tab is open.
 *
 * The clock is injected, so the interval is tested by moving time rather than by sleeping.
 */
class SessionHealthTest {
    private val clock = MovableClock()
    private val asked = AtomicInteger()

    @Test
    fun `the first call asks`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.ALIVE)

        health.state() shouldBe SessionState.ALIVE
        asked.get() shouldBe 1
    }

    @Test
    fun `a second call inside the interval answers from cache`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.ALIVE)

        repeat(10) { health.state() }
        clock.advance(Duration.ofMinutes(4))
        health.state()

        asked.get() shouldBe 1
    }

    @Test
    fun `the interval expiring asks again`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.ALIVE)

        health.state()
        clock.advance(Duration.ofMinutes(5))
        health.state()

        asked.get() shouldBe 2
    }

    /**
     * An expired answer is cached like any other: it is a fact about the credential, not a
     * failure of the check, and re-asking every heartbeat would hammer Programmers precisely
     * when something is already wrong.
     */
    @Test
    fun `an expired answer is cached too`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.EXPIRED)

        health.state() shouldBe SessionState.EXPIRED
        health.state() shouldBe SessionState.EXPIRED

        asked.get() shouldBe 1
    }

    /**
     * The one that must not be remembered. A probe that could not run says nothing, and holding
     * that nothing for five minutes would report nothing useful for five minutes.
     */
    @Test
    fun `an unknown answer is not cached`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.UNKNOWN)

        health.state()
        health.state()
        health.state()

        asked.get() shouldBe 3
    }

    /** A failed probe must not leave a stale ALIVE standing until the interval runs out. */
    @Test
    fun `a failure after a good answer re-asks on the next call`() = runBlocking<Unit> {
        val answers = ArrayDeque(listOf(SessionState.ALIVE, SessionState.UNKNOWN, SessionState.EXPIRED))
        val health = SessionHealth({
            asked.incrementAndGet()
            answers.removeFirst()
        }, clock)

        health.state() shouldBe SessionState.ALIVE
        clock.advance(Duration.ofMinutes(6))
        health.state() shouldBe SessionState.UNKNOWN
        health.state() shouldBe SessionState.EXPIRED

        asked.get() shouldBe 3
    }

    private fun healthAnswering(state: SessionState) = SessionHealth({
        asked.incrementAndGet()
        state
    }, clock)

    private class MovableClock : Clock() {
        private var now = Instant.parse("2026-08-11T09:00:00Z")

        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
