package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.support.fixtures.aRunErrorText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Run-path error text arrives HTML-escaped (protocol doc §7). Classification and storage
 * both read the text, so the escaping is undone once, here, at the parse boundary.
 */
class HtmlTextTest {
    @Test
    fun `restores the newlines a measured compiler diagnostic arrived with as br tags`() {
        val diagnostic = aRunErrorText(0)

        diagnostic shouldNotContain "<br/>"
        diagnostic shouldContain "/Solution.java:3: error: ';' expected\n"
    }

    @Test
    fun `restores the quotes a measured stack trace arrived with as entities`() {
        val trace = aRunErrorText(1)

        trace shouldNotContain "&quot;"
        trace shouldContain """Exception in thread "main" java.lang.ArrayIndexOutOfBoundsException"""
    }

    // Rails escapes exactly these five entities; the remaining two have no measured
    // capture, so they are covered here rather than by a fixture (dev rules §6.2).
    @Test
    fun `decodes the remaining rails entities`() {
        HtmlText.unescape("&lt;T&gt;") shouldBe "<T>"
    }

    @Test
    fun `decodes an escaped ampersand last so a double-escaped entity survives one pass`() {
        HtmlText.unescape("&amp;quot;") shouldBe "&quot;"
    }

    @Test
    fun `leaves text without entities untouched`() {
        HtmlText.unescape("plain text") shouldBe "plain text"
    }
}
