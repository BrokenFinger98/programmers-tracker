package com.brokenfinger.tracker.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SessionStateTest {
    @Test
    fun `a live session is authenticated`() {
        SessionState.ALIVE.authenticated() shouldBe true
    }

    @Test
    fun `an expired session is not`() {
        SessionState.EXPIRED.authenticated() shouldBe false
    }

    /**
     * The direction that matters. An unreachable check is not a dead cookie, and reporting it as
     * one teaches the user to ignore the one message that means "replace your credential".
     */
    @Test
    fun `an unknown answer is not reported as expired`() {
        SessionState.UNKNOWN.authenticated() shouldBe true
    }

    /** Pins the set, so a new state has to decide which side of the line it belongs on. */
    @Test
    fun `only EXPIRED is unauthenticated`() {
        SessionState.entries.filterNot { it.authenticated() } shouldContainExactly listOf(SessionState.EXPIRED)
    }
}
