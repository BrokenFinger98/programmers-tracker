package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.matchers.shouldBe
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Only the subscription lifecycle is this class's business — what a frame *means* is
 * [com.brokenfinger.tracker.application.ChannelCapture]'s, and it is tested there.
 */
class CableChannelSubscriberTest {
    private val identifier = anAlgorithmIdentifier()

    @Test
    fun `subscribing twice holds a single observation`() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val subscriber = subscriberOver(CoroutineScope(Dispatchers.Default)) {
            opened.incrementAndGet()
            emptyFlow()
        }

        subscriber.subscribe(identifier)
        subscriber.subscribe(identifier)
        delay(SETTLE_MS)

        opened.get() shouldBe 1
    }

    @Test
    fun `unsubscribing cancels the observation so an evicted channel stops being watched`() = runBlocking<Unit> {
        val cancelled = AtomicInteger()
        val subscriber = subscriberOver(CoroutineScope(Dispatchers.Default)) {
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

    /** A dropped subscription must not take the others down (protocol doc §11). */
    @Test
    fun `a failing observation does not stop another channel`() = runBlocking<Unit> {
        val alive = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Default)
        val subscriber = subscriberOver(scope) { channel ->
            failingOrLiveFlow(channel, alive)
        }

        subscriber.subscribe(anAlgorithmIdentifier(lessonId = 120001))
        subscriber.subscribe(anAlgorithmIdentifier(lessonId = 120002))
        delay(SETTLE_MS)

        alive.get() shouldBe 1
    }

    private fun failingOrLiveFlow(channel: ChannelIdentifier, alive: AtomicInteger): Flow<CableEvent> = flow {
        if (channel.lessonId.value == 120001L) throw IllegalStateException("socket died")
        alive.incrementAndGet()
        delay(FOREVER_MS)
    }

    private fun subscriberOver(
        scope: CoroutineScope,
        observation: (ChannelIdentifier) -> Flow<CableEvent>,
    ): CableChannelSubscriber {
        val client = mockk<ActionCableClient>()
        every { client.observe(any(), any()) } answers { observation(firstArg()) }
        return CableChannelSubscriber(client, { mockk(relaxed = true) }, scope) { mockk(relaxed = true) }
    }

    private companion object {
        const val SETTLE_MS = 200L
        const val FOREVER_MS = 60_000L
    }
}
