package com.brokenfinger.tracker.domain

import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

/**
 * Unit test for the channel identity (dev rules §6.1, 0 mocks). Strict validation — these
 * are values we build ourselves rather than values received (dev rules §4), so every
 * violation throws here rather than being carried further as a plausible-looking number.
 */
class ChannelKeyTest {
    @Test
    fun `rejects non-positive lesson id`() {
        shouldThrow<IllegalArgumentException> { LessonId(0) }
    }

    @Test
    fun `rejects non-positive challengeable id`() {
        shouldThrow<IllegalArgumentException> { ChallengeableId(-1) }
    }

    @Test
    fun `rejects blank language`() {
        shouldThrow<IllegalArgumentException> {
            ChannelKey.of(LessonId(120804), ChallengeableId(14643), ProblemKind.ALGORITHM, " ")
        }
    }
}
