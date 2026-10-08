package com.brokenfinger.tracker.adapter.git

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * What a search for the push token ended in (#360, #373), and where a token was found, as far as a refusal
 * says it: never what it found.
 */
internal sealed interface SearchOutcome {
    /** Everything it was asked to read was read, and nothing was found. */
    data object Clean : SearchOutcome

    /** Not everything it was asked to read was read, so nothing it was asked about may go out. */
    data object Unsearched : SearchOutcome

    /** A token shape or a stored value was found in a file's content: a blob, or what a commit stages. */
    data object FoundInContent : SearchOutcome
}

/**
 * What `git cat-file --batch` printed, read against the objects it was asked for and searched as it is
 * read (#373). Each object is its header, `<id> <type> <size>`, its content and a newline. Anything else —
 * an object reported `missing`, a header other than the one asked for, content that ends early, output
 * left over — is output that is not what was asked for, and the search is [SearchOutcome.Unsearched].
 *
 * Content is read [window] bytes at a time, each window repeating the last [TokenPatterns.overlap] bytes
 * of the one before: memory stays at a window however large the object, and a match across a seam lies
 * whole in one window. Each object is searched apart, as `git grep` searches each file, so nothing runs
 * from one into the next.
 */
internal class BatchOutput(
    private val stream: InputStream,
    private val patterns: TokenPatterns,
    private val window: Int = WINDOW,
) {
    /** The first match, or the first thing that is not as asked; [SearchOutcome.Clean] when there is neither. */
    fun searched(objects: List<GitObject>): SearchOutcome {
        for (each in objects) {
            val outcome = searched(each)
            if (outcome != SearchOutcome.Clean) return outcome
        }
        if (stream.read() != END) return SearchOutcome.Unsearched
        return SearchOutcome.Clean
    }

    // [asked] as printed: its header, its content, searched, and the newline after it.
    private fun searched(asked: GitObject): SearchOutcome {
        if (header() != asked.header()) return SearchOutcome.Unsearched
        val outcome = contentSearched(asked.size)
        if (outcome != SearchOutcome.Clean) return outcome
        if (stream.read() != NEWLINE) return SearchOutcome.Unsearched
        return SearchOutcome.Clean
    }

    // The line before an object's content, read no further than a header can be long; null when it ends first.
    private fun header(): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < HEADER_LIMIT) {
            val next = stream.read()
            if (next == NEWLINE) return line.toString(Charsets.ISO_8859_1)
            if (next == END) return null
            line.write(next)
        }
        return null
    }

    // [size] bytes of content, a window at a time; Unsearched when the output ends before they do.
    private fun contentSearched(size: Long): SearchOutcome {
        var left = size
        var tail = ""
        while (left > 0) {
            val read = bytes(minOf(left, window.toLong()).toInt()) ?: return SearchOutcome.Unsearched
            val text = tail + read
            if (patterns.foundIn(text)) return SearchOutcome.FoundInContent
            tail = text.takeLast(patterns.overlap)
            left -= read.length
        }
        return SearchOutcome.Clean
    }

    // Exactly [count] bytes, one ISO-8859-1 character each; null when the output ends first.
    private fun bytes(count: Int): String? {
        val read = stream.readNBytes(count)
        if (read.size < count) return null
        return String(read, Charsets.ISO_8859_1)
    }

    companion object {
        /** A megabyte of content at a time: the memory a search holds, whatever the size of an object. */
        const val WINDOW = 1 shl 20

        /** Longer than any header: a 64-character id, `commit`, and a size of 19 digits. */
        private const val HEADER_LIMIT = 256

        private const val NEWLINE = '\n'.code

        private const val END = -1
    }
}
