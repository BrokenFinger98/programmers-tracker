package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The identifiers a `/watch` caller used to have to paste out of DevTools (#114), read from
 * the page instead. Both captures are measured markup (2026-08-10), because a hand-written
 * `<div data-challengeable-id="1">` would only prove the regex matches the page we imagined.
 */
class ProblemIdentityPageTest {
    /** Measured on 120803 — the value the protocol doc's §3 table records for it. */
    @Test
    fun `reads an algorithm problem's identifiers`() {
        val html = FixtureLoader.text("lesson-page-identifiers.html")

        ProblemIdentityPage.identityOf(html) shouldBe ProblemIdentity(14642, ProblemKind.ALGORITHM)
    }

    /** Measured on 131528 — the same lesson the SQL capture fixtures come from. */
    @Test
    fun `reads a database problem's identifiers`() {
        val html = FixtureLoader.text("lesson-page-identifiers-sql.html")

        ProblemIdentityPage.identityOf(html) shouldBe ProblemIdentity(2778, ProblemKind.DATABASE)
    }

    /**
     * A sign-in redirect body has no identifiers, and inventing one there would subscribe to
     * a channel belonging to whatever problem happened to own that number.
     */
    @Test
    fun `a page carrying no identifiers is null, not a guess`() {
        ProblemIdentityPage.identityOf("<html><body>로그인이 필요합니다</body></html>").shouldBeNull()
    }

    /**
     * Programmers serves nine channel families and we have measured two (protocol §3).
     * Defaulting a third to ALGORITHM would build a subscription that is confirmed and then
     * silently never delivers the frame we wait for — the §3 trap, one level up.
     */
    @Test
    fun `an unmeasured problem family is refused rather than defaulted`() {
        val html = """<div data-challengeable-id="99" data-challengeable-type="essay"></div>"""

        ProblemIdentityPage.identityOf(html).shouldBeNull()
    }

    /** The id must be a number; a malformed attribute is a page we do not understand. */
    @Test
    fun `a non-numeric id is refused`() {
        val html = """<div data-challengeable-id="abc" data-challengeable-type="algorithm"></div>"""

        ProblemIdentityPage.identityOf(html).shouldBeNull()
    }
}
