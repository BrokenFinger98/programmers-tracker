package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Drives the socket-boundary flow with a fake socket fed measured captures (dev rules §6.2). */
class RawSocketEventsTest {
    private val identifier = anAlgorithmIdentifier()

    @Test
    fun `subscribes exactly once after welcome`() {
        val socket = FakeRawSocket(FixtureLoader.rawFrames("algorithm-pass.jsonl"))

        runBlocking { socket.events(SubscriptionProtocol(identifier)).toList() }

        socket.sent shouldBe listOf(CableCommand.subscribe(identifier))
    }

    @Test
    fun `emits confirmation and every broadcast of the capture in order`() {
        val lines = FixtureLoader.rawFrames("algorithm-pass.jsonl")
        val socket = FakeRawSocket(lines)

        val events = runBlocking { socket.events(SubscriptionProtocol(identifier)).toList() }

        // welcome + ping produce no events: 9 captured lines → 1 confirm + 6 broadcasts.
        events shouldHaveSize 7
        events.first().shouldBeInstanceOf<CableEvent.SubscriptionConfirmed>()
        events.drop(1).map { it.shouldBeInstanceOf<CableEvent.MessageReceived>().rawText } shouldBe lines.drop(3)
    }

    @Test
    fun `rejection fails the flow`() {
        val socket = FakeRawSocket(listOf("""{"identifier":"{}","type":"reject_subscription"}"""))

        shouldThrow<SubscriptionRejectedException> {
            runBlocking { socket.events(SubscriptionProtocol(identifier)).toList() }
        }
    }

    @Test
    fun `a closed socket completes the flow`() {
        val socket = FakeRawSocket(emptyList())

        val events = runBlocking { socket.events(SubscriptionProtocol(identifier)).toList() }

        events shouldHaveSize 0
    }
}

private class FakeRawSocket(lines: List<String>) : RawSocket {
    val sent = mutableListOf<String>()
    private val queue = ArrayDeque(lines)

    override suspend fun receive(): String? = queue.removeFirstOrNull()

    override suspend fun send(text: String) {
        sent += text
    }
}
