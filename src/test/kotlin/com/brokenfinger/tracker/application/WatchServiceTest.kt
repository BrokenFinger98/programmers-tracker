package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChallengeableId
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.LessonId
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.SensorObservation
import com.brokenfinger.tracker.domain.SessionState
import com.brokenfinger.tracker.domain.SubscriptionHealth
import com.brokenfinger.tracker.support.fixtures.anAlgorithmChannel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
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
    private val identities = RecordingResolver()
    private val service = WatchService(registry, subscriber, timer, identities, aLiveSession(), SteppingClock())

    @Test
    fun `a first watch subscribes and reports that it started`() = runBlocking<Unit> {
        service.watch(aCommand(lessonId = 120804)).outcome shouldBe WatchOutcome.STARTED

        subscriber.calls shouldContainExactly listOf("subscribe:120804")
    }

    /** The extension re-posts every 30 s per open tab (design §4.1). */
    /** Without this the clock never starts and every record reports a measured-looking 0. */
    @Test
    fun `watching starts the problem timer`() = runBlocking<Unit> {
        service.watch(aCommand(lessonId = 120804))

        timer.started shouldContainExactly listOf(120804L)
    }

    @Test
    fun `a repeat watch refreshes without touching the socket`() = runBlocking<Unit> {
        service.watch(aCommand(lessonId = 120804))
        subscriber.calls.clear()

        service.watch(aCommand(lessonId = 120804)).outcome shouldBe WatchOutcome.REFRESHED

        subscriber.calls shouldContainExactly emptyList()
    }

    @Test
    fun `an eviction unsubscribes the evicted channel before subscribing the newcomer`() = runBlocking<Unit> {
        fillEverySlot()

        service.watch(aCommand(lessonId = 120999))

        // 120801 holds the oldest heartbeat, and the order proves the cap is never exceeded.
        subscriber.calls shouldContainExactly listOf("unsubscribe:120801", "subscribe:120999")
    }

    @Test
    fun `a channel with a live grading is never evicted`() = runBlocking<Unit> {
        fillEverySlot()
        registry.markActive(channelOf(120801))

        service.watch(aCommand(lessonId = 120999))

        subscriber.calls shouldContainExactly listOf("unsubscribe:120802", "subscribe:120999")
    }

    /** Silently declining to observe would let the user believe a solve was recorded. */
    @Test
    fun `saturation fails loudly and changes no subscription`() = runBlocking<Unit> {
        fillEverySlot()
        (1..8).forEach { registry.markActive(channelOf(120800L + it)) }

        shouldThrow<WatchCapacityExceededException> { service.watch(aCommand(lessonId = 120999)) }

        subscriber.calls shouldContainExactly emptyList()
    }

    private suspend fun fillEverySlot() {
        (1..8).forEach { service.watch(aCommand(lessonId = 120800L + it)) }
        subscriber.calls.clear()
    }

    private fun channelOf(lessonId: Long) = anAlgorithmChannel(lessonId = lessonId)

    private fun aCommand(lessonId: Long) = WatchCommand(lessonId = lessonId, language = "java")

    /**
     * Answers the measured identifiers of 120804 for any lesson, and counts the calls — the
     * service must resolve before it decides anything, and must not resolve twice for a
     * heartbeat it can already answer from the registry (#114).
     */
    private class RecordingResolver(
        private val answer: ResolvedProblem? = ResolvedProblem(ChallengeableId(14643), ProblemKind.ALGORITHM),
    ) : ProblemIdentityResolver {
        var calls = 0

        override suspend fun resolve(lessonId: LessonId, language: String): ResolvedProblem? {
            calls += 1
            return answer
        }
    }

    private class RecordingTimer : ProblemTimer {
        val started = mutableListOf<Long>()

        override fun elapsedSecOf(lessonId: Long): Long = 0

        override fun startIfAbsent(lessonId: Long) {
            started += lessonId
        }

        override fun observed(lessonId: Long, observation: SensorObservation) {
            observations[lessonId] = observation
        }

        override fun observationOf(lessonId: Long): SensorObservation? = observations[lessonId]

        val observations = mutableMapOf<Long, SensorObservation>()
    }

    /**
     * The defect #167 removed: `started` was returned whether or not the socket lived, so a
     * refused subscription and a working one were the same answer. The service must report
     * what the subscriber says rather than what the registry decided.
     */
    @Test
    fun `the answer carries the subscription's own verdict, not the registry's`() = runBlocking<Unit> {
        val refused = RecordingSubscriber(health = SubscriptionHealth.REJECTED)
        val service = WatchService(SubscriptionRegistry(), refused, timer, identities, aLiveSession(), SteppingClock())

        val status = service.watch(aCommand(lessonId = 120804))

        status.outcome shouldBe WatchOutcome.STARTED
        status.health shouldBe SubscriptionHealth.REJECTED
        status.health.observing() shouldBe false
    }

    /** The session half is #179's business and has its own tests; here it is simply healthy. */
    private fun aLiveSession() = SessionHealth({ SessionState.ALIVE }, Clock.systemUTC())

    private class RecordingSubscriber(private val health: SubscriptionHealth = SubscriptionHealth.LIVE) :
        ChannelSubscriber {
        val calls = mutableListOf<String>()

        override fun subscribe(channel: ChannelKey) {
            calls += "subscribe:${channel.lessonId.value}"
        }

        override fun unsubscribe(channel: ChannelKey) {
            calls += "unsubscribe:${channel.lessonId.value}"
        }

        override fun healthOf(channel: ChannelKey) = health
    }

    /** Each read advances a second, so heartbeat order is deterministic without sleeping. */
    private class SteppingClock : Clock() {
        private var current = Instant.parse("2026-08-05T00:00:00Z")

        override fun instant(): Instant = current.also { current = current.plusSeconds(1) }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
