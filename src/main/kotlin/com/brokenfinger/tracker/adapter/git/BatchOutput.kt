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

    /** A token shape or a stored value was found in [part] of the commit [commit], its id (#375). */
    data class FoundInCommit(val commit: String, val part: CommitPart) : SearchOutcome

    /**
     * A token shape or a stored value was found in a file or directory name: a tree's entries (#375), or a
     * path a commit adds (#376).
     */
    data object FoundInName : SearchOutcome
}

/** Which part of a commit a token was found in, as a refusal names it (#375). */
internal enum class CommitPart(val said: String) {
    /** What follows the empty line that ends the header. */
    MESSAGE("the message"),

    /** The author and committer lines, and any other header line, such as a tag merged into the commit. */
    HEADER("the author, committer or another header line"),
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

    // [asked] as printed: its header, its content, searched as what it is, and the newline after it.
    private fun searched(asked: GitObject): SearchOutcome {
        if (header() != asked.header()) return SearchOutcome.Unsearched
        val outcome = contentSearched(asked.size, searchFor(asked))
        if (outcome != SearchOutcome.Clean) return outcome
        if (stream.read() != NEWLINE) return SearchOutcome.Unsearched
        return SearchOutcome.Clean
    }

    // How [asked]'s content is searched: a commit's header apart from its message, a tree for names, a blob whole.
    private fun searchFor(asked: GitObject): ContentSearch {
        if (asked.type == COMMIT) return CommitSearch(asked.id, patterns)
        if (asked.type == TREE) return NameSearch(patterns)
        return BlobSearch(patterns)
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

    // [size] bytes of content, a window at a time, each handed to [search]; Unsearched when the output ends first.
    private fun contentSearched(size: Long, search: ContentSearch): SearchOutcome {
        var left = size
        while (left > 0) {
            val read = bytes(minOf(left, window.toLong()).toInt()) ?: return SearchOutcome.Unsearched
            val outcome = search.next(read)
            if (outcome != SearchOutcome.Clean) return outcome
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

        private const val COMMIT = "commit"

        private const val TREE = "tree"
    }
}

/** How one object's content is searched as it is read, a window at a time. */
private interface ContentSearch {
    /** What [window], the object's next bytes as ISO-8859-1 characters, completes: a finding, or Clean. */
    fun next(window: String): SearchOutcome
}

/**
 * Text searched a window at a time by [matches], each window repeating the end of the one before, so nothing
 * across a seam is missed. [matches] is [TokenPatterns.foundIn] unless the text is a tree's.
 */
private class Stretch(
    private val patterns: TokenPatterns,
    private val matches: (String) -> Boolean = patterns::foundIn,
) {
    private var tail = ""

    /** Whether [text], the next characters of this stretch, completes a match. */
    fun found(text: String): Boolean {
        val joined = tail + text
        if (matches(joined)) return true
        tail = joined.takeLast(patterns.overlap)
        return false
    }
}

/** A blob: what is found in it is found in a file's content. */
private class BlobSearch(patterns: TokenPatterns) : ContentSearch {
    private val content = Stretch(patterns)

    override fun next(window: String): SearchOutcome =
        SearchOutcome.FoundInContent.takeIf { content.found(window) } ?: SearchOutcome.Clean
}

/**
 * A tree: its entries, each a mode, a name, a NUL and a binary object id, read as bytes alone (#375). A run of
 * token characters that touches an id ends at the NUL before it and the space after the next mode — 26 bytes at
 * most, against the 40 a token needs — so what is found is found in a name.
 */
private class NameSearch(patterns: TokenPatterns) : ContentSearch {
    private val names = Stretch(patterns, patterns::foundInBytes)

    override fun next(window: String): SearchOutcome =
        SearchOutcome.FoundInName.takeIf { names.found(window) } ?: SearchOutcome.Clean
}

/**
 * A commit, its header — up to the empty line that ends it — searched apart from its message, so a match is
 * said as the part it is in (#375). No match spans that empty line: neither a shape nor a stored value holds
 * a newline. The empty line can fall across a seam, its first newline the last byte of a window.
 */
private class CommitSearch(private val id: String, patterns: TokenPatterns) : ContentSearch {
    private val header = Stretch(patterns)
    private val message = Stretch(patterns)
    private var inMessage = false
    private var endedLine = false

    override fun next(window: String): SearchOutcome {
        if (inMessage) return found(message, window, CommitPart.MESSAGE)
        val end = endOfHeader(window)
        endedLine = window.endsWith('\n')
        if (end < 0) return found(header, window, CommitPart.HEADER)
        inMessage = true
        return split(window, end)
    }

    // Where in [window] the empty line that ends the header ends — its second newline — or -1.
    private fun endOfHeader(window: String): Int {
        if (endedLine && window.startsWith('\n')) return 0
        val at = window.indexOf("\n\n")
        if (at < 0) return -1
        return at + 1
    }

    private fun split(window: String, end: Int): SearchOutcome {
        val before = found(header, window.substring(0, end), CommitPart.HEADER)
        if (before != SearchOutcome.Clean) return before
        return found(message, window.substring(end + 1), CommitPart.MESSAGE)
    }

    private fun found(part: Stretch, text: String, which: CommitPart): SearchOutcome =
        SearchOutcome.FoundInCommit(id, which).takeIf { part.found(text) } ?: SearchOutcome.Clean
}
