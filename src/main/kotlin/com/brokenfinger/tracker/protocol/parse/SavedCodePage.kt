package com.brokenfinger.tracker.protocol.parse

/**
 * Reads the user's last saved code out of a problem page.
 *
 * Broadcasts carry no source code, so the page is the only source (protocol doc §10). Two
 * measured properties of that markup break a naive parser and drive this implementation:
 * the `<input>` tag spans several lines, and `value` contains **raw** newlines rather than
 * escaped ones — see `fixtures/lesson-page-saved-code.html`.
 *
 * The same page also carries `initial_code_<id>`, the untouched problem skeleton. Only the
 * input marked `data-type="code"` is the user's work.
 */
object SavedCodePage {
    private val codeInput = Regex("""<input\b[^>]*\bdata-type="code"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private val valueAttribute = Regex("""\bvalue="([^"]*)"""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The saved code, or null when the page has no editor at all — a login redirect body is
     * the common case, and returning "" there would record an empty solution as if it were
     * real (design §4.4).
     */
    fun savedCodeOf(html: String): String? {
        val tag = codeInput.find(html)?.value ?: return null
        val escaped = valueAttribute.find(tag)?.groupValues?.get(1) ?: return null
        return HtmlText.unescape(escaped)
    }
}
