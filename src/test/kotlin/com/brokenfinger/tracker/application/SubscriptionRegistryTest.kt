package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.support.fixtures.anAlgorithmChannel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class SubscriptionRegistryTest {
    @Test
    fun `the first watch of an identifier opens a subscription and evicts nothing`() {
        val registry = SubscriptionRegistry()

        registry.watch(channel(1), at(0)) shouldBe WatchResult.Started(evicted = null)
    }

    @Test
    fun `a repeat for an already watched identifier does not duplicate it`() {
        val registry = SubscriptionRegistry()
        registry.watch(channel(1), at(0))

        registry.watch(channel(1), at(30)) shouldBe WatchResult.AlreadyWatching
        registry.snapshot().map { it.channel } shouldContainExactly listOf(channel(1))
    }

    @Test
    fun `a repeat refreshes recency, so the refreshed entry is no longer the eviction victim`() {
        val registry = SubscriptionRegistry(capacity = 2)
        registry.watch(channel(1), at(0))
        registry.watch(channel(2), at(10))

        registry.watch(channel(1), at(20)) shouldBe WatchResult.AlreadyWatching

        registry.watch(channel(3), at(30)) shouldBe WatchResult.Started(evicted = channel(2))
    }

    @Test
    fun `at capacity a new identifier evicts the oldest heartbeat and reports which one`() {
        val registry = filled(capacity = 8)

        registry.watch(channel(9), at(100)) shouldBe WatchResult.Started(evicted = channel(1))
    }

    @Test
    fun `the evicted identifier is gone from the registry and the newcomer took its slot`() {
        val registry = filled(capacity = 8)

        registry.watch(channel(9), at(100))

        val watched = registry.snapshot().map { it.channel }
        watched.size shouldBe 8
        watched.contains(channel(1)) shouldBe false
        watched.contains(channel(9)) shouldBe true
    }

    @Test
    fun `a pinned oldest entry is skipped and the next-oldest unpinned one is evicted instead`() {
        val registry = filled(capacity = 8)
        registry.markActive(channel(1))

        registry.watch(channel(9), at(100)) shouldBe WatchResult.Started(evicted = channel(2))
        registry.snapshot().map { it.channel }.contains(channel(1)) shouldBe true
    }

    @Test
    fun `every slot pinned yields saturation rather than a silent drop`() {
        val registry = filled(capacity = 8)
        (1..8).forEach { registry.markActive(channel(it)) }

        registry.watch(channel(9), at(100)) shouldBe WatchResult.Saturated
    }

    @Test
    fun `a saturated registry keeps every pinned subscription untouched`() {
        val registry = filled(capacity = 8)
        (1..8).forEach { registry.markActive(channel(it)) }

        registry.watch(channel(9), at(100))

        registry.snapshot().map { it.channel } shouldContainExactly (1..8).map { channel(it) }
    }

    @Test
    fun `settling unpins the entry, so it becomes evictable again`() {
        val registry = filled(capacity = 8)
        registry.markActive(channel(1))
        registry.markSettled(channel(1))

        registry.watch(channel(9), at(100)) shouldBe WatchResult.Started(evicted = channel(1))
    }

    @Test
    fun `marking a session active on an unwatched identifier fails loudly`() {
        val registry = SubscriptionRegistry()

        shouldThrow<IllegalStateException> { registry.markActive(channel(1)) }
    }

    @Test
    fun `unwatch removes the subscription and frees the slot`() {
        val registry = filled(capacity = 2)

        registry.unwatch(channel(1)) shouldBe true

        registry.watch(channel(9), at(100)) shouldBe WatchResult.Started(evicted = null)
    }

    @Test
    fun `unwatch of an identifier we never watched reports that there was nothing to drop`() {
        val registry = SubscriptionRegistry()

        registry.unwatch(channel(1)) shouldBe false
    }

    @Test
    fun `unwatch drops a pinned entry too — the caller settled it by closing the tab`() {
        val registry = filled(capacity = 2)
        registry.markActive(channel(1))

        registry.unwatch(channel(1)) shouldBe true
        registry.snapshot().map { it.channel } shouldContainExactly listOf(channel(2))
    }

    @Test
    fun `the snapshot lists entries oldest first and carries the pin state for diagnostics`() {
        val registry = SubscriptionRegistry()
        registry.watch(channel(1), at(20))
        registry.watch(channel(2), at(10))
        registry.markActive(channel(2))

        val snapshot = registry.snapshot()

        snapshot.map { it.channel } shouldContainExactly listOf(channel(2), channel(1))
        snapshot.map { it.pinned } shouldContainExactly listOf(true, false)
        snapshot.first().lastHeartbeat shouldBe at(10)
    }

    @Test
    fun `a non-positive capacity is rejected at construction`() {
        shouldThrow<IllegalArgumentException> { SubscriptionRegistry(capacity = 0) }
    }

    // Distinct problems, one heartbeat second apart each, so eviction order is unambiguous.
    private fun filled(capacity: Int): SubscriptionRegistry {
        val registry = SubscriptionRegistry(capacity)
        (1..capacity).forEach { registry.watch(channel(it), at(it.toLong())) }
        return registry
    }

    // One channel per problem ------------------------------------------------------------------

    /**
     * A language switch **replaces** the channel it switches from (#158).
     *
     * Measured 2026-08-11 on lesson 120805: the problem was opened in Java and then in
     * Python3, both subscriptions stayed live, and one Python run produced **two** records —
     * one of them labelled `java` and carrying Python's traceback. A record for code that was
     * never run is the worst thing this tool can produce.
     *
     * A person solves in one language at a time. Solving the same problem in Kotlin and then
     * in Java is two gradings and stays two records; what must never happen is one grading
     * becoming two.
     */
    @Test
    fun `a second language on one problem supersedes the first`() {
        val registry = SubscriptionRegistry()
        val java = anAlgorithmChannel(lessonId = 120805, challengeableId = 14645, language = "java")
        val python = java.copy(language = "python3")

        registry.watch(java, at(1))
        val result = registry.watch(python, at(2))

        result shouldBe WatchResult.Started(evicted = java)
        registry.snapshot().map { it.channel } shouldContainExactly listOf(python)
    }

    /** Another problem is untouched — the rule is per problem, not global. */
    @Test
    fun `a language switch leaves other problems watched`() {
        val registry = SubscriptionRegistry()
        val other = channel(1)
        val java = anAlgorithmChannel(lessonId = 120805, challengeableId = 14645, language = "java")

        registry.watch(other, at(1))
        registry.watch(java, at(2))
        registry.watch(java.copy(language = "kotlin"), at(3))

        registry.snapshot().map { it.channel.lessonId.value }.toSet() shouldBe
            setOf(other.lessonId.value, 120805L)
    }

    private fun channel(n: Int): ChannelKey = anAlgorithmChannel(lessonId = 120800L + n, challengeableId = 14600L + n)

    private fun at(second: Long): Instant = Instant.EPOCH.plusSeconds(second)
}
