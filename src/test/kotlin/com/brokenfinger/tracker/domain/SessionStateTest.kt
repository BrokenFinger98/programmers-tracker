package com.brokenfinger.tracker.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SessionStateTest {
    /** UNKNOWN stays authenticated: a probe that could not run says nothing about the cookie. */
    @Test
    fun `only a dead or absent credential is unauthenticated`() {
        SessionState.ALIVE.authenticated() shouldBe true
        SessionState.UNKNOWN.authenticated() shouldBe true
        SessionState.EXPIRED.authenticated() shouldBe false
        SessionState.MISSING.authenticated() shouldBe false
    }
}
