package com.brokenfinger.tracker.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The whole point of this type is the line between "a grading would be captured" and "it
 * would not", so that line is what is tested — including the state that could plausibly fall
 * on either side.
 */
class SubscriptionHealthTest {
    @Test
    fun `a live subscription would capture a grading`() {
        SubscriptionHealth.LIVE.observing() shouldBe true
    }

    /**
     * A subscription a moment old is not a fault. Treating it as one would make the first
     * heartbeat of every problem the user opens look like a broken sensor.
     */
    @Test
    fun `a subscription that has not answered yet still counts as observing`() {
        SubscriptionHealth.PENDING.observing() shouldBe true
    }

    /**
     * Pins the failing set rather than each member, so adding a state without deciding which
     * side of the line it belongs on fails here instead of silently reading as healthy.
     */
    @Test
    fun `exactly the two failures are not observing`() {
        SubscriptionHealth.entries.filterNot { it.observing() } shouldContainExactly
            listOf(SubscriptionHealth.REJECTED, SubscriptionHealth.UNREACHABLE)
    }
}
