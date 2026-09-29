package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SessionState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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

    /**
     * #334 — the extension posts one heartbeat per open tab, each served by its own thread. On a
     * cold or just-reset cache they all found nothing cached and all probed: eight tabs, eight
     * requests to Programmers for one answer. One probe is in flight at a time; the rest wait for it.
     */
    @Test
    fun `concurrent callers on an empty cache share one probe`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val health = SessionHealth({
            asked.incrementAndGet()
            release.await()
            SessionState.ALIVE
        }, clock)

        val callers = (1..8).map { async(Dispatchers.Default) { health.state() } }
        awaitAsked(1)
        release.complete(Unit)

        callers.awaitAll().toSet() shouldBe setOf(SessionState.ALIVE)
        asked.get() shouldBe 1
    }

    /** #334 — a probe that fails for one caller must not leave the others waiting forever. */
    @Test
    fun `a probe that throws releases the callers waiting on it, who ask again`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        var first = true
        val health = SessionHealth({
            asked.incrementAndGet()
            if (first) {
                first = false
                release.await()
                throw IllegalStateException("the first probe blew up")
            }
            SessionState.ALIVE
        }, clock)

        val prober = async(Dispatchers.Default) { runCatching { health.state() } }
        awaitAsked(1)
        val waiter = async(Dispatchers.Default) { health.state() }
        delay(50)
        release.complete(Unit)

        prober.await().isFailure shouldBe true
        waiter.await() shouldBe SessionState.ALIVE
        asked.get() shouldBe 2
    }

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

    /** #331 — a cached EXPIRED is about a cookie that has just been replaced; repeating it for five minutes is wrong. */
    @Test
    fun `a replaced credential forgets the cached answer`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.EXPIRED)

        health.state() shouldBe SessionState.EXPIRED
        health.credentialReplaced()
        health.state()

        asked.get() shouldBe 2
    }

    /** Review of #331: an answer in flight when the credential was replaced is about the old one. */
    @Test
    fun `an answer in flight when the credential was replaced is not remembered`() = runBlocking<Unit> {
        lateinit var health: SessionHealth
        var replaceMidProbe = true
        health = SessionHealth({
            asked.incrementAndGet()
            if (replaceMidProbe) {
                replaceMidProbe = false
                health.credentialReplaced()
            }
            SessionState.EXPIRED
        }, clock)

        health.state() shouldBe SessionState.EXPIRED
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

    // A check that has stopped being able to answer (#189) --------------------------------

    /**
     * The endpoint this rests on is one Programmers never promised us. If it moves, every probe
     * returns UNKNOWN, the badge stays quiet by design, and the expired-cookie detection is gone
     * with nothing saying so.
     */
    @Test
    fun `a sustained run of unknown is reported once`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.UNKNOWN)

        health.state()
        clock.advance(Duration.ofMinutes(31))
        health.state()

        health.muteChanged() shouldBe true
        health.muteChanged().shouldBeNull()
    }

    /** A laptop losing wifi for a moment is not a protocol change. */
    @Test
    fun `a short blip says nothing`() = runBlocking<Unit> {
        val health = healthAnswering(SessionState.UNKNOWN)

        health.state()
        clock.advance(Duration.ofMinutes(5))
        health.state()

        health.muteChanged().shouldBeNull()
    }

    /** The all-clear, for the same reason the backup has one: a warning with no end is unreadable. */
    @Test
    fun `recovering is reported once`() = runBlocking<Unit> {
        val answers = ArrayDeque(listOf(SessionState.UNKNOWN, SessionState.UNKNOWN, SessionState.ALIVE))
        val health = SessionHealth({
            asked.incrementAndGet()
            answers.removeFirst()
        }, clock)

        health.state()
        clock.advance(Duration.ofMinutes(31))
        health.state()
        health.muteChanged() shouldBe true

        health.state()

        health.muteChanged() shouldBe false
        health.muteChanged().shouldBeNull()
    }

    /** EXPIRED is the check working, not failing — it must end the run like ALIVE does. */
    @Test
    fun `an expired answer ends the run of unknowns`() = runBlocking<Unit> {
        val answers = ArrayDeque(listOf(SessionState.UNKNOWN, SessionState.EXPIRED, SessionState.UNKNOWN))
        val health = SessionHealth({
            asked.incrementAndGet()
            answers.removeFirst()
        }, clock)

        health.state()
        clock.advance(Duration.ofMinutes(20))
        health.state()
        clock.advance(Duration.ofMinutes(20))
        health.state()

        health.muteChanged().shouldBeNull()
    }

    private suspend fun awaitAsked(target: Int) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (asked.get() < target) {
            if (System.nanoTime() > deadline) error("probe never started")
            delay(5)
        }
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
