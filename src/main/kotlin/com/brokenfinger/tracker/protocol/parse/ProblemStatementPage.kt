package com.brokenfinger.tracker.protocol.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Reads a problem's statement off its page and hands back Markdown (#275).
 *
 * **The records knew how you failed and not what was asked.** Every MCP tool returned metadata
 * and gradings; none of them could say what the problem wanted, which is the largest missing
 * input on that whole surface and the one thing a reader of the vault most obviously needs.
 *
 * The region is `div.markdown.solarized-dark` — Programmers renders the author's Markdown into
 * HTML and leaves the class name saying so, which is why converting it back is well defined
 * rather than a guess. Measured 2026-08-13 across lessons 120802, 12916, 151136, 17676 and
 * 42894: `p · hr · br · h2–h5 · ul · li · code · strong · u · a · img · table/thead/tbody/tr/th/td`,
 * and **no nested `div`**, which is what makes the region's boundary unambiguous.
 *
 * Parsed with jsoup rather than a regex. The two parsers beside this one read a single
 * attribute off a single tag, which regex does honestly; a nested document is not that, and
 * matching nested markup by hand is the mistake
 * [[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]] was written about.
 * jsoup also never throws on malformed input, which is the posture dev rules §2 asks of
 * everything that reads Programmers.
 *
 * **What it does not recognise, it keeps.** An unmeasured tag is emitted as its own HTML rather
 * than dropped — Markdown carries inline HTML and Obsidian renders it, so a statement using
 * something new degrades to "looks slightly raw" instead of losing a paragraph (dev rules §2.3).
 */
object ProblemStatementPage {
    private const val REGION = "div.markdown.solarized-dark"

    /**
     * Null when the page carries no statement — a sign-in redirect is the common case, and an
     * empty file would be indistinguishable from a problem that has no description.
     */
    fun statementOf(html: String): String? {
        val region = Jsoup.parse(html).selectFirst(REGION) ?: return null
        return markdownOf(region).trim().ifEmpty { null }
    }

    private fun markdownOf(region: Element): String =
        region.childNodes().joinToString("") { block(it) }.replace(BLANK_RUN, "\n\n")

    /** Block level: everything that wants its own line, and a blank line after it. */
    private fun block(node: Node): String = when (nameOf(node)) {
        "p" -> inlineOf(node) + "\n\n"
        "hr" -> "---\n\n"
        "h1", "h2", "h3", "h4", "h5", "h6" -> headingOf(node)
        "ul" -> listOf(node, "- ")
        "ol" -> listOf(node, "1. ")
        "table" -> tableOf(node as Element) + "\n"
        "blockquote" -> "> " + inlineOf(node).replace("\n", "\n> ") + "\n\n"
        "pre" -> "```\n" + (node as Element).wholeText().trim() + "\n```\n\n"
        // The newlines the renderer leaves between blocks. Every block above ends itself, so
        // passing these through would only add blank lines to collapse again.
        "#text" -> if ((node as TextNode).wholeText.isBlank()) "" else inline(node)
        else -> inline(node)
    }

    /** The level the page gave it, skips included — the source markdown skips them too. */
    private fun headingOf(node: Node): String =
        "#".repeat(nameOf(node).last().digitToInt()) + " " + inlineOf(node) + "\n\n"

    /** Continuation lines are indented so a `<br>` inside an item does not end the item. */
    private fun listOf(node: Node, marker: String): String =
        (node as Element).children().joinToString("") { marker + inlineOf(it).replace("\n", "\n  ") + "\n" } + "\n"

    private fun tableOf(table: Element): String {
        val rows = table.select("tr").map { row -> row.children().map { inlineOf(it).replace("|", "\\|") } }
        if (rows.isEmpty()) return ""
        val header = rows.first()
        val rule = header.map { "---" }
        return (listOf(header, rule) + rows.drop(1)).joinToString("\n") { it.joinToString(" | ", "| ", " |") } + "\n"
    }

    /**
     * Whitespace is collapsed the way a browser collapses it, so the only newline left is the
     * one a `<br>` asked for. Without this a `<br>` followed by a source newline — which is how
     * the renderer writes every one of them — becomes a blank line and ends the list item.
     */
    private fun inlineOf(node: Node): String =
        node.childNodes().joinToString("") { inline(it) }.replace(AROUND_BREAK, "\n").trim()

    private fun inline(node: Node): String = when (nameOf(node)) {
        "#text" -> (node as TextNode).wholeText.replace(WHITESPACE_RUN, " ")
        "code" -> "`" + (node as Element).wholeText() + "`"
        "strong", "b" -> "**" + inlineOf(node) + "**"
        "em", "i" -> "*" + inlineOf(node) + "*"
        // Deliberately unmarked: Programmers uses `<u>` for emphasis inside a sentence and
        // Markdown has no underline, so the alternatives are raw HTML or bold that was not asked for.
        "u", "span" -> inlineOf(node)
        "br" -> "\n"
        // A block inside a list item — measured on lesson 17676, whose every example is a
        // `<p>` wrapped in an `<li>`. Left to the fallback it printed raw HTML into the note.
        "p" -> inlineOf(node) + "\n"
        "a" -> "[${inlineOf(node)}](${(node as Element).attr("href")})"
        "img" -> "![${(node as Element).attr("alt")}](${node.attr("src")})"
        "#comment" -> ""
        else -> if (node is Element) node.outerHtml() else ""
    }

    private fun nameOf(node: Node): String = node.nodeName().lowercase()

    private val BLANK_RUN = Regex("\n{3,}")

    /** Insignificant whitespace, as HTML defines it — `<pre>` never comes through here. */
    private val WHITESPACE_RUN = Regex("\\s+")

    private val AROUND_BREAK = Regex(" *\n *")
}
