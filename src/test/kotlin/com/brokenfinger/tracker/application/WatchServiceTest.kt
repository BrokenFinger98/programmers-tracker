package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The registry decides what should be held; this decides when the socket changes. The
 * ordering matters — an eviction has to unsubscribe *before* the newcomer subscribes, or the
 * cap is briefly exceeded.
 */
class WatchServiceTest {
    private val registry = SubscriptionRegistry()
    private val subscriber = RecordingSubscriber()
    private val timer = RecordingTimer()
    private val service = WatchService(registry, subscriber, timer, SteppingClock())

    @Test
    fun `a first watch subscribes and reports that it started`() {
        service.watch(aCommand(lessonId = 120804)) shouldBe WatchOutcome.STARTED

        subscriber.calls shouldContainExactly listOf("subscribe:120804")
    }

    /** The extension re-posts every 30 s per open tab (design §4.1). */
    /** Without this the clock never starts and every record reports a measured-looking 0. */
    @Test
    fun `watching starts the problem timer`() {
        service.watch(aCommand(lessonId = 120804))

        timer.started shouldContainExactly listOf(120804L)
    }

    @Test
    fun `a repeat watch refreshes without touching the socket`() {
        service.watch(aCommand(lessonId = 120804))
        subscriber.calls.clear()

        service.watch(aCommand(lessonId = 120804)) shouldBe WatchOutcome.REFRESHED

        subscriber.calls shouldContainExactly emptyList()
    }

    @Test
    fun `an eviction unsubscribes the evicted channel before subscribing the newcomer`() {
        fillEverySlot()

        service.watch(aCommand(lessonId = 120999))

        // 120801 holds the oldest heartbeat, and the order proves the cap is never exceeded.
        subscriber.calls shouldContainExactly listOf("unsubscribe:120801", "subscribe:120999")
    }

    @Test
    fun `a channel with a live grading is never evicted`() {
        fillEverySlot()
        registry.markActive(identifierOf(120801))

        service.watch(aCommand(lessonId = 120999))

        subscriber.calls shouldContainExactly listOf("unsubscribe:120802", "subscribe:120999")
    }

    /** Silently declining to observe would let the user believe a solve was recorded. */
    @Test
    fun `saturation fails loudly and changes no subscription`() {
        fillEverySlot()
        (1..8).forEach { registry.markActive(identifierOf(120800L + it)) }

        shouldThrow<WatchCapacityExceededException> { service.watch(aCommand(lessonId = 120999)) }

        subscriber.calls shouldContainExactly emptyList()
    }

    private fun fillEverySlot() {
        (1..8).forEach { service.watch(aCommand(lessonId = 120800L + it)) }
        subscriber.calls.clear()
    }

    private fun identifierOf(lessonId: Long) = anAlgorithmIdentifier(lessonId = lessonId)

    private fun aCommand(lessonId: Long) = WatchCommand(
        lessonId = lessonId,
        challengeableId = 14643,
        kind = ProblemKind.ALGORITHM,
        language = "java",
        codesKey = "49598",
    )

    private class RecordingTimer : ProblemTimer {
        val started = mutableListOf<Long>()

        override fun elapsedSecOf(lessonId: Long): Long = 0

        override fun startIfAbsent(lessonId: Long) {
            started += lessonId
        }
    }

    private class RecordingSubscriber : ChannelSubscriber {
        val calls = mutableListOf<String>()

        override fun subscribe(identifier: ChannelIdentifier) {
            calls += "subscribe:${identifier.lessonId.value}"
        }

        override fun unsubscribe(identifier: ChannelIdentifier) {
            calls += "unsubscribe:${identifier.lessonId.value}"
        }
    }

    /** Each read advances a second, so heartbeat order is deterministic without sleeping. */
    private class SteppingClock : Clock() {
        private var current = Instant.parse("2026-08-05T00:00:00Z")

        override fun instant(): Instant = current.also { current = current.plusSeconds(1) }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
