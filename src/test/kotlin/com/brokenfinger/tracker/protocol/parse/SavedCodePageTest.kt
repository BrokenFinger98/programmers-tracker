package com.brokenfinger.tracker.protocol.parse

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Extraction is driven by a measured excerpt of the real problem page
 * (`fixtures/lesson-page-saved-code.html`, protocol doc §10). Hand-written HTML would only
 * prove we can parse the markup we imagined — and the two properties that break a naive
 * parser are exactly the ones a hand-written sample would omit: the tag spans several lines
 * and `value` contains raw newlines.
 */
class SavedCodePageTest {
    private val page = readFixture("lesson-page-saved-code.html")

    @Test
    fun `extracts the saved code from a multi-line input tag`() {
        val code = SavedCodePage.savedCodeOf(page)

        code shouldBe
            """
            class Solution {
                public int solution(int num1, int num2) {
                    return num1 * num2;
                }
            }
            """.trimIndent()
    }

    /**
     * Guards the fixture itself. `.gitattributes` marks captures `-text` so no checkout
     * platform rewrites them; without that, Windows (`core.autocrlf=true`, CI runners
     * included) turns the raw newlines inside `value` into CRLF and the capture stops
     * saying what Programmers actually served. Failing here names the cause directly
     * instead of surfacing as a confusing string mismatch.
     */
    @Test
    fun `the fixture still holds the bytes Programmers served`() {
        page shouldNotContain "\r\n"
    }

    @Test
    fun `does not confuse the skeleton input with the saved one`() {
        // The page carries both: `initial_code_<id>` is the untouched problem skeleton and
        // only the `data-type="code"` input holds what the user actually saved (§10).
        val code = SavedCodePage.savedCodeOf(page)!!

        code shouldContain "return num1 * num2;"
        code shouldNotContain "int answer"
    }

    @Test
    fun `unescapes entities inside the value attribute`() {
        val html = """<input data-type="code" value="print(&quot;hi&quot; + &#39;x&#39;)"/>"""

        SavedCodePage.savedCodeOf(html) shouldBe """print("hi" + 'x')"""
    }

    @Test
    fun `attribute order does not matter`() {
        val html = """<input value="x = 1" type="hidden" data-type="code"/>"""

        SavedCodePage.savedCodeOf(html) shouldBe "x = 1"
    }

    /** A login redirect body has no editor at all — the caller must not store an empty string. */
    @Test
    fun `a page without the code input yields null rather than an empty string`() {
        SavedCodePage.savedCodeOf("<html><body>Sign in</body></html>").shouldBeNull()
    }

    @Test
    fun `an empty saved value is still a value, not a missing input`() {
        SavedCodePage.savedCodeOf("""<input data-type="code" value=""/>""") shouldBe ""
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }
}
