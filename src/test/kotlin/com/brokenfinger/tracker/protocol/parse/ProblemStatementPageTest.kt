package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The problem statement, read out of the page we already know how to fetch (#275).
 *
 * Driven by a **measured** capture whose prose is substituted — the shape is what this parses
 * and the words are Programmers' (dev rules §7.3, and #275 forbids a real statement in this
 * repository outright).
 */
class ProblemStatementPageTest {
    private val statement = ProblemStatementPage.statementOf(FixtureLoader.text("lesson-page-statement.html"))!!

    @Test
    fun `a page with no statement region is absent rather than empty`() {
        ProblemStatementPage.statementOf("<html><body>sign in first</body></html>").shouldBeNull()
    }

    /** The sign-in redirect body is the common failure, and an empty file would look like a statement. */
    @Test
    fun `a statement region with nothing in it is absent too`() {
        ProblemStatementPage.statementOf("""<div class="markdown solarized-dark">  </div>""").shouldBeNull()
    }

    @Test
    fun `paragraphs become paragraphs and inline code keeps its backticks`() {
        statement shouldContain "정수 `alpha`와 `beta`가 주어질 때, 두 값을 **합친 결과**를 return 하도록"
    }

    @Test
    fun `headings keep the level the page gave them, skips included`() {
        statement shouldContain "\n##### 제한사항\n"
        statement shouldContain "\n#### 입출력 예\n"
        statement shouldContain "\n### 참고\n"
        statement shouldContain "\n## 주의\n"
    }

    @Test
    fun `list items become a markdown list`() {
        statement shouldContain "- -1,000 ≤ `alpha` ≤ 1,000"
    }

    /** A `<br>` inside a list item is a line break, not the end of the item. */
    @Test
    fun `a line break inside an item stays inside it`() {
        statement shouldContain "- -1,000 ≤ `beta` ≤ 1,000\n  두 값은 서로 달라도 괜찮습니다."
    }

    /**
     * Measured on lesson 17676, whose every worked example is a `<p>` inside an `<li>`. Left to
     * the keep-what-you-do-not-know fallback it printed raw `<p>` and `<br>` into the note.
     */
    @Test
    fun `a paragraph nested in a list item is the item, not raw html`() {
        // The final `]` follows a source newline rather than a `<br>`, and HTML collapses that
        // to a space — which is what a browser shows and therefore what the note should say.
        statement shouldContain "- 결과: [\n  7,\n  11 ]"
        statement shouldNotContain "<p>"
    }

    @Test
    fun `a table becomes a markdown table with its header rule`() {
        statement shouldContain
            """
            | alpha | beta | result |
            | --- | --- | --- |
            | 7 | 11 | 18 |
            | -4 | 4 | 0 |
            """.trimIndent()
    }

    @Test
    fun `rules, links and images survive`() {
        statement shouldContain "\n---\n"
        statement shouldContain "[여기](https://example.invalid/reading)"
        statement shouldContain "![예시 그림](https://grepp-programmers.s3.example/example-diagram.png)"
    }

    /** Whatever else the page carried, it is not in here. */
    @Test
    fun `nothing outside the statement region comes along`() {
        statement shouldNotContain "guide-section"
        statement shouldNotContain "문제 설명"
        statement shouldNotContain "<div"
    }

    /** Trailing blank lines would grow a file that is rewritten by nobody. */
    @Test
    fun `it is trimmed`() {
        statement shouldBe statement.trim()
    }
}
