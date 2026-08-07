package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.ChannelIdentifier
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
        const val FAILING = 120001L
        const val HEALTHY = 120002L
    }
}
