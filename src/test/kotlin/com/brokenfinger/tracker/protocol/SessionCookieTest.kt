package com.brokenfinger.tracker.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    // #331 — an observation has to notice that the file now holds a different cookie, without
    // anything holding either value.
    @Test
    fun `the fingerprint tells two cookies apart and is stable for one`() {
        SessionCookie(fakeCookie).fingerprint() shouldBe SessionCookie(fakeCookie).fingerprint()
        SessionCookie(fakeCookie).fingerprint() shouldNotBe SessionCookie("_session_production=another").fingerprint()
    }

    @Test
    fun `the fingerprint does not contain the value`() {
        SessionCookie(fakeCookie).fingerprint() shouldNotContain "fake-value-for-tests"
    }
}
