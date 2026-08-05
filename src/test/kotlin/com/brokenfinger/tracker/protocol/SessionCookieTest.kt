package com.brokenfinger.tracker.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class SessionCookieTest {
    // Synthetic value — never a real credential (dev rules §7.3).
    private val fakeCookie = "_session_production=fake-value-for-tests"

    // Dev rules §7.2 — the cookie must never appear in logs or exceptions at any level.
    @Test
    fun `masks the value in toString`() {
        SessionCookie(fakeCookie).toString() shouldBe "SessionCookie(***)"
    }

    @Test
    fun `toString never contains the raw value`() {
        SessionCookie(fakeCookie).toString() shouldNotContain "fake-value-for-tests"
    }

    @Test
    fun `exposes the raw value only through headerValue`() {
        SessionCookie(fakeCookie).headerValue() shouldBe fakeCookie
    }

    @Test
    fun `rejects a blank cookie`() {
        shouldThrow<IllegalArgumentException> { SessionCookie(" ") }
    }
}
