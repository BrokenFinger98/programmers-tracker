package com.brokenfinger.tracker.protocol.parse

/**
 * Undoes the escaping Programmers applies to run-path error text (protocol doc §7):
 * newlines arrive as `<br/>`, quotes as `&quot;` / `&#39;`.
 *
 * The entity set is Rails' `html_escape` output — the five it emits, no more. A general
 * HTML decoder here would claim coverage of markup we have never observed on this channel.
 */
object HtmlText {
    // `&amp;` is decoded last so a double-escaped entity unwinds exactly one level per pass
    // rather than collapsing into a character that was never sent.
    private val entities = listOf(
        "<br/>" to "\n",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&lt;" to "<",
        "&gt;" to ">",
        "&amp;" to "&",
    )

    fun unescape(raw: String): String = entities.fold(raw) { text, (entity, decoded) ->
        text.replace(entity, decoded)
    }
}
