package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.SubscriptionHealth
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.SubscriptionRejectedException
import com.brokenfinger.tracker.support.fixtures.anAlgorithmChannel
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Only the subscription lifecycle is this class's business — what a frame *means* belongs to
 * [ChannelCapture] and is tested there.
 *
 * Two rules keep these tests fast and stable, both learned the hard way (#27):
 *
 * - **Reconnection is awaited, never slept for.** The wait between attempts is injected and
 *   completes a signal, so a test finishes the moment the behaviour happens instead of after
 *   a fixed delay that is too short on a loaded runner and wasted everywhere else.
 * - **Every scope is cancelled.** The retry loop is infinite by design; a scope left running
 *   spins for the rest of the suite. An earlier version of this file did exactly that and
 *   took a CI runner past ten minutes.
 */
class CableChannelSubscriberTest {
    private val channel = anAlgorithmChannel()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stopObserving() = scope.cancel()

    @Test
    fun `subscribing twice holds a single observation`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver {
            opened.incrementAndGet()
            neverEnding()
        }

        subscriber.subscribe(channel)
        subscriber.subscribe(channel)
        awaitAtLeast(opened, 1)

        opened.get() shouldBe 1
    }

    @Test
    fun `unsubscribing cancels the observation so an evicted channel stops being watched`() = runBlocking<Unit> {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val subscriber = subscriberOver {
            flow<CableEvent> {
                started.incrementAndGet()
                try {
                    delay(FOREVER_MS)
                } finally {
                    cancelled.incrementAndGet()
                }
            }
        }

        subscriber.subscribe(channel)
        awaitAtLeast(started, 1)
        subscriber.unsubscribe(channel)
        awaitAtLeast(cancelled, 1)

        cancelled.get() shouldBe 1
    }

    /**
     * Unsubscribing is not a dropped connection, and `runCatching` could not tell the
     * difference: it caught the `CancellationException` our own [CableChannelSubscriber.unsubscribe]
     * raises and ran the failure path. Measured across seven language switches on 2026-08-12 —
     * a WARN each about broadcasts that were never at risk, and one more pass of the retry loop
     * (#217).
     *
     * The retry pass is what this asserts, because it is the half with teeth: it calls
     * `connectionLost()`, which settles a grading still in flight as INCOMPLETE.
     *
     * **A deliberate sleep, and the file's rule says not to.** It is the same exception the
     * heartbeat test takes, for the same reason: both assert that something *never* happens,
     * and a non-event has no signal to await. The flow's own unwinding is the sync point, so
     * the settle only has to cover the statements between it and the retry — with the defect
     * present, they run back to back.
     */
    @Test
    fun `unsubscribing is a stop, not a dropped connection`() = runBlocking<Unit> {
        val started = AtomicInteger()
        val unwound = AtomicInteger()
        val attempts = AtomicInteger()
        val capture = mockk<ChannelCapture>(relaxed = true)
        val subscriber = subscriberOver(capture = capture, attempts = attempts) {
            flow {
                started.incrementAndGet()
                try {
                    delay(FOREVER_MS)
                } finally {
                    unwound.incrementAndGet()
                }
            }
        }

        subscriber.subscribe(channel)
        awaitAtLeast(started, 1)
        subscriber.unsubscribe(channel)
        awaitAtLeast(unwound, 1)
        delay(SETTLE_MS)

        coVerify(exactly = 0) { capture.connectionLost() }
        attempts.get() shouldBe 0
    }

    /**
     * A socket was measured closing silently after ~30 minutes — no exception, no close frame.
     * A flow that simply completes is that case, and accepting it would leave the channel
     * unobserved while everything looked healthy.
     */
    @Test
    fun `an observation that ends quietly is retried rather than accepted`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver {
            opened.incrementAndGet()
            emptyFlow()
        }

        subscriber.subscribe(channel)

        awaitAtLeast(opened, 2)
    }

    @Test
    fun `a failing observation is retried too`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver {
            opened.incrementAndGet()
            flow<CableEvent> { throw IllegalStateException("socket died") }
        }

        subscriber.subscribe(channel)

        awaitAtLeast(opened, 2)
    }

    /** Silence past the deadline is failure, not calm — the client must not wait forever. */
    @Test
    fun `silence beyond the deadline ends the attempt and reconnects`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver(deadline = Duration.ofMillis(50)) {
            opened.incrementAndGet()
            neverEnding()
        }

        subscriber.subscribe(channel)

        awaitAtLeast(opened, 2)
    }

    /**
     * The defect this class's own doc described backwards (#94): the deadline was applied
     * to a flow the ping never reached, so it measured the gap between *gradings* and fired
     * on every idle channel. A heartbeat must hold the socket open — and must not reach the
     * capture, which records what it is given.
     */
    @Test
    fun `heartbeats hold the socket open and never reach the capture`() = runBlocking<Unit> {
        val capture = mockk<ChannelCapture>(relaxed = true)
        val reconnects = AtomicInteger()
        val subscriber = subscriberOver(deadline = Duration.ofMillis(200), capture = capture, attempts = reconnects) {
            flow {
                while (true) {
                    emit(CableEvent.Heartbeat("""{"type":"ping","message":1}"""))
                    delay(POLL_MS)
                }
            }
        }

        subscriber.subscribe(channel)
        delay(600)

        reconnects.get() shouldBe 0
        verify(exactly = 0) { runBlocking { capture.onFrame(any()) } }
    }

    /** A grading in flight when the socket drops must settle, never wait for a result. */
    @Test
    fun `a dropped connection tells the capture so an open grading settles`() = runBlocking<Unit> {
        val capture = mockk<ChannelCapture>(relaxed = true)
        val reconnects = AtomicInteger()
        val subscriber = subscriberOver(capture = capture, attempts = reconnects) { emptyFlow() }

        subscriber.subscribe(channel)
        awaitAtLeast(reconnects, 1)

        coVerify(atLeast = 1) { capture.connectionLost() }
    }

    @Test
    fun `one channel's failure does not stop another`() = runBlocking<Unit> {
        val alive = AtomicInteger()
        val subscriber = subscriberOver { channel -> failingOrLive(channel, alive) }

        subscriber.subscribe(anAlgorithmChannel(lessonId = FAILING))
        subscriber.subscribe(anAlgorithmChannel(lessonId = HEALTHY))

        awaitAtLeast(alive, 1)
    }

    // --- subscription health (#167) ------------------------------------------------------
    //
    // `/watch` answered `started` whether the socket confirmed, was refused, or never opened,
    // so an expired cookie looked exactly like a working sensor. These pin the four answers
    // that make it conditional, including the one that must survive the retry loop.

    /**
     * The optimistic default is the defect. A channel this class holds no job for is not
     * being watched, and PENDING here would have the badge say it is.
     */
    @Test
    fun `a channel nobody subscribed to is not reported as observing`() {
        val subscriber = subscriberOver { neverEnding() }

        subscriber.healthOf(channel) shouldBe SubscriptionHealth.UNREACHABLE
    }

    @Test
    fun `a frame arriving makes the subscription live`() = runBlocking<Unit> {
        val subscriber = subscriberOver {
            flow {
                emit(CableEvent.Heartbeat("""{"type":"ping","message":1}"""))
                delay(FOREVER_MS)
            }
        }

        subscriber.subscribe(channel)
        awaitHealth(subscriber, SubscriptionHealth.LIVE)
    }

    /**
     * The one the user can act on: a refusal means the session cookie, and retrying with the
     * same one cannot succeed. It must not read as a flaky network.
     */
    @Test
    fun `a refused subscription is reported as rejected`() = runBlocking<Unit> {
        val subscriber = subscriberOver {
            flow<CableEvent> { throw SubscriptionRejectedException("the judge refused the subscription") }
        }

        subscriber.subscribe(channel)
        awaitHealth(subscriber, SubscriptionHealth.REJECTED)
    }

    @Test
    fun `any other failure is reported as unreachable`() = runBlocking<Unit> {
        val subscriber = subscriberOver { flow<CableEvent> { throw IllegalStateException("socket died") } }

        subscriber.subscribe(channel)
        awaitHealth(subscriber, SubscriptionHealth.UNREACHABLE)
    }

    /**
     * The ~30-minute silent close throws nothing, so nothing would demote the channel — it
     * would keep reporting whatever it last said while connected to nothing.
     */
    @Test
    fun `an attempt that ends without a single frame stops counting as observing`() = runBlocking<Unit> {
        val subscriber = subscriberOver { emptyFlow() }

        subscriber.subscribe(channel)
        awaitHealth(subscriber, SubscriptionHealth.UNREACHABLE)
    }

    /**
     * The retry loop runs continuously, so a refusal must not blink out of view between
     * attempts. Re-marking PENDING at the top of each attempt would pass every other test
     * here and leave the badge green on an expired cookie — the original defect, restored.
     */
    @Test
    fun `a refusal survives the reconnect that follows it`() = runBlocking<Unit> {
        val attempts = AtomicInteger()
        val subscriber = subscriberOver(attempts = attempts) {
            flow<CableEvent> { throw SubscriptionRejectedException("the judge refused the subscription") }
        }

        subscriber.subscribe(channel)
        awaitAtLeast(attempts, 3)

        subscriber.healthOf(channel) shouldBe SubscriptionHealth.REJECTED
    }

    /** Pasting a fresh cookie must heal it without a restart — only a frame clears a failure. */
    @Test
    fun `a rejected channel becomes live again once frames arrive`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver {
            if (opened.incrementAndGet() == 1) {
                flow { throw SubscriptionRejectedException("the judge refused the subscription") }
            } else {
                flow {
                    emit(CableEvent.Heartbeat("""{"type":"ping","message":1}"""))
                    delay(FOREVER_MS)
                }
            }
        }

        subscriber.subscribe(channel)
        awaitHealth(subscriber, SubscriptionHealth.LIVE)
    }

    @Test
    fun `unsubscribing forgets the channel rather than leaving a stale health`() = runBlocking<Unit> {
        val started = AtomicInteger()
        val subscriber = subscriberOver {
            flow {
                started.incrementAndGet()
                emit(CableEvent.Heartbeat("""{"type":"ping","message":1}"""))
                delay(FOREVER_MS)
            }
        }

        subscriber.subscribe(channel)
        awaitHealth(subscriber, SubscriptionHealth.LIVE)
        subscriber.unsubscribe(channel)

        subscriber.healthOf(channel) shouldBe SubscriptionHealth.UNREACHABLE
    }

    private suspend fun awaitHealth(subscriber: CableChannelSubscriber, target: SubscriptionHealth) {
        withTimeout(PATIENCE_MS) {
            while (subscriber.healthOf(channel) != target) delay(POLL_MS)
        }
        subscriber.healthOf(channel) shouldBe target
    }

    /** Waits for the behaviour itself; the timeout only bounds a genuine failure. */
    private suspend fun awaitAtLeast(counter: AtomicInteger, target: Int) {
        withTimeout(PATIENCE_MS) {
            while (counter.get() < target) delay(POLL_MS)
        }
    }

    private fun failingOrLive(channel: ChannelKey, alive: AtomicInteger): Flow<CableEvent> = flow {
        if (channel.lessonId.value == FAILING) throw IllegalStateException("socket died")
        alive.incrementAndGet()
        delay(FOREVER_MS)
    }

    private fun neverEnding(): Flow<CableEvent> = flow { delay(FOREVER_MS) }

    private fun subscriberOver(
        deadline: Duration = Duration.ofSeconds(30),
        capture: ChannelCapture = mockk(relaxed = true),
        attempts: AtomicInteger = AtomicInteger(),
        observation: (ChannelKey) -> Flow<CableEvent>,
    ): CableChannelSubscriber {
        val client = mockk<ActionCableClient>()
        // The subscriber is handed a key and must build the wire form itself, so the stub
        // unwraps what it was actually called with rather than assuming the two match.
        every { client.observe(any(), any()) } answers { observation(firstArg<ChannelIdentifier>().key) }
        return CableChannelSubscriber(
            client = client,
            sessions = mockk(relaxed = true),
            scope = scope,
            silenceDeadline = deadline,
            // The schedule belongs to ConnectionLiveness and is tested there. Here the wait is
            // replaced by a short yield: skipping it entirely turns the retry loop into a busy
            // spin that outlives the test.
            waitFor = {
                attempts.incrementAndGet()
                delay(POLL_MS)
            },
            captureFor = { capture },
        )
    }

    private companion object {
        const val FOREVER_MS = 60_000L
        const val POLL_MS = 5L
        const val PATIENCE_MS = 10_000L
        const val SETTLE_MS = 300L
        const val FAILING = 120001L
        const val HEALTHY = 120002L
    }
}
