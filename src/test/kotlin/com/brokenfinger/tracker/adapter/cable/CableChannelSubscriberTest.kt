package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Only the subscription lifecycle is this class's business — what a frame *means* belongs to
 * [ChannelCapture] and is tested there.
 *
 * No test waits out a backoff: the wait is injected, so reconnection is observed by counting
 * attempts rather than by spending their duration.
 */
class CableChannelSubscriberTest {
    private val identifier = anAlgorithmIdentifier()

    @Test
    fun `subscribing twice holds a single observation`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver {
            opened.incrementAndGet()
            neverEnding()
        }

        subscriber.subscribe(identifier)
        subscriber.subscribe(identifier)
        delay(SETTLE_MS)

        opened.get() shouldBe 1
    }

    @Test
    fun `unsubscribing cancels the observation so an evicted channel stops being watched`() = runBlocking<Unit> {
        val cancelled = AtomicInteger()
        val subscriber = subscriberOver {
            flow<CableEvent> {
                try {
                    delay(FOREVER_MS)
                } finally {
                    cancelled.incrementAndGet()
                }
            }
        }

        subscriber.subscribe(identifier)
        delay(SETTLE_MS)
        subscriber.unsubscribe(identifier)
        delay(SETTLE_MS)

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

        subscriber.subscribe(identifier)
        delay(SETTLE_MS)

        opened.get() shouldBeGreaterThan 1
    }

    @Test
    fun `a failing observation is retried too`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver {
            opened.incrementAndGet()
            flow<CableEvent> { throw IllegalStateException("socket died") }
        }

        subscriber.subscribe(identifier)
        delay(SETTLE_MS)

        opened.get() shouldBeGreaterThan 1
    }

    /** Silence past the deadline is failure, not calm — the client must not wait forever. */
    @Test
    fun `silence beyond the deadline ends the attempt and reconnects`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver(deadline = Duration.ofMillis(60)) {
            opened.incrementAndGet()
            neverEnding()
        }

        subscriber.subscribe(identifier)
        delay(SETTLE_MS)

        opened.get() shouldBeGreaterThan 1
    }

    /** A grading in flight when the socket drops must settle, never wait for a result. */
    @Test
    fun `a dropped connection tells the capture so an open grading settles`() = runBlocking<Unit> {
        val capture = mockk<ChannelCapture>(relaxed = true)
        val subscriber = subscriberOver(capture = capture) { emptyFlow() }

        subscriber.subscribe(identifier)
        delay(SETTLE_MS)

        coVerify(atLeast = 1) { capture.connectionLost() }
    }

    @Test
    fun `one channel's failure does not stop another`() = runBlocking<Unit> {
        val alive = AtomicInteger()
        val subscriber = subscriberOver { channel -> failingOrLive(channel, alive) }

        subscriber.subscribe(anAlgorithmIdentifier(lessonId = FAILING))
        subscriber.subscribe(anAlgorithmIdentifier(lessonId = HEALTHY))
        delay(SETTLE_MS)

        alive.get() shouldBeGreaterThan 0
    }

    private fun failingOrLive(channel: ChannelIdentifier, alive: AtomicInteger): Flow<CableEvent> = flow {
        if (channel.lessonId.value == FAILING) throw IllegalStateException("socket died")
        alive.incrementAndGet()
        delay(FOREVER_MS)
    }

    private fun neverEnding(): Flow<CableEvent> = flow { delay(FOREVER_MS) }

    private fun subscriberOver(
        deadline: Duration = Duration.ofSeconds(30),
        capture: ChannelCapture = mockk(relaxed = true),
        observation: (ChannelIdentifier) -> Flow<CableEvent>,
    ): CableChannelSubscriber {
        val client = mockk<ActionCableClient>()
        every { client.observe(any(), any()) } answers { observation(firstArg()) }
        return CableChannelSubscriber(
            client = client,
            sessions = mockk(relaxed = true),
            scope = CoroutineScope(Dispatchers.Default),
            silenceDeadline = deadline,
            // The schedule is ConnectionLiveness's business and tested there; skipping the
            // wait lets reconnection be observed without spending its duration.
            waitFor = {},
            captureFor = { capture },
        )
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val FOREVER_MS = 60_000L
        const val FAILING = 120001L
        const val HEALTHY = 120002L
    }
}
