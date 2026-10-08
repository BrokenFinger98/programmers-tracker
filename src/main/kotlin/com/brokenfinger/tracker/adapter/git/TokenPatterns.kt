package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.git.StoredCredential.Patterns
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * What the content gate looks for, and how a window of what a push would send is searched for it (#360,
 * #373): anything shaped like a GitHub token, and each value the store holds.
 *
 * **A window is bytes, each read as the ISO-8859-1 character of the same number.** Nothing is decoded, so
 * no byte is dropped or merged with its neighbours, and a match is found where `git grep` finds one in the
 * C locale: in a binary blob as well, and beside an invalid UTF-8 sequence, where `git grep -E` in a UTF-8
 * locale on macOS found nothing (git 2.48.1, #372's review). The stored values are searched for as the
 * UTF-8 bytes `git grep -F -f -` is fed, split into lines as it splits them.
 *
 * **A window that holds a NUL byte is read as UTF-16 as well** — in both byte orders, from its first byte
 * and from its second — and searched for the shapes and for the stored values as text. `git grep` finds
 * no token in UTF-16 text in any locale, and Windows PowerShell 5.1 writes UTF-16 with every `>`. UTF-16
 * text with an ASCII character in it always holds a NUL byte, so no other window is read twice.
 */
internal class TokenPatterns private constructor(private val values: List<String>) {
    private val valuesAsBytes = values.map(::asBytes)

    /**
     * How many bytes a window repeats from the end of the one before, so that whatever straddles the seam
     * lies whole in one of them: one fewer than the longest a shortest match can be — of a shape or of a
     * stored value, as bytes or as UTF-16, where each character takes two.
     */
    val overlap: Int = (values.flatMap(::spansOf) + LARGEST_MINIMUM_MATCH * 2).max() - 1

    /** Whether [window] — bytes, as ISO-8859-1 characters — holds a token shape or a stored value. */
    fun foundIn(window: String): Boolean =
        holds(window, valuesAsBytes) || NUL in window && asUtf16(window).any { holds(it, values) }

    /**
     * Whether [window] holds a token shape or a stored value as its bytes alone, never read as UTF-16: for a
     * tree, whose names cannot hold a NUL, so no UTF-16 text of an ASCII character can be in one (#375).
     */
    fun foundInBytes(window: String): Boolean = holds(window, valuesAsBytes)

    private fun holds(text: String, fixed: List<String>): Boolean =
        COMPILED.any { it.matcher(text).find() } || fixed.any { it in text }

    companion object {
        /**
         * GitHub's token formats, which `git grep -E` and `java.util.regex` read alike: they are not secret,
         * so they go in argv. The classic kinds are a prefix, `_`, then 30 random and 6 checksum characters
         * (GitHub's engineering blog), 36 in all; `_` is in the class because a stateless installation token
         * is `ghs_<app id>_<JWT>`, whose JWT header alone runs past 36. A fine-grained token is `github_pat_`
         * and more; its length is not documented, and the bound stays at 60.
         */
        val SHAPES = listOf("gh[pousr]_[A-Za-z0-9_]{36,}", "github_pat_[A-Za-z0-9_]{60,}")

        /**
         * The largest of the shapes' shortest matches, in characters: `github_pat_` and 60 more. A longer run
         * matches only where its first 71 characters already do, so these are what a window must hold.
         */
        const val LARGEST_MINIMUM_MATCH = 71

        fun of(stored: StoredCredential): TokenPatterns = TokenPatterns(valuesOf(stored))

        private const val NUL = '\u0000'

        private val COMPILED = SHAPES.map(Pattern::compile)

        private val UTF_16 = listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)

        // As `git grep -F -f -` reads the store's input: a line each, less a CR before the newline, none empty.
        private fun valuesOf(stored: StoredCredential): List<String> {
            if (stored !is Patterns) return emptyList()
            return stored.asInput().split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
        }

        private fun asBytes(value: String): String = String(value.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

        private fun spansOf(value: String): List<Int> = listOf(asBytes(value).length, value.length * 2)

        // The window's bytes read as UTF-16, in both byte orders, from its first byte and from its second.
        private fun asUtf16(window: String): Sequence<String> {
            val bytes = window.toByteArray(Charsets.ISO_8859_1)
            return sequenceOf(0, 1).flatMap { skip -> UTF_16.asSequence().map { decoded(bytes, skip, it) } }
        }

        private fun decoded(bytes: ByteArray, skip: Int, charset: Charset): String =
            String(bytes, skip, bytes.size - skip, charset)
    }
}
