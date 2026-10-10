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
 * from one into the next. A tree's names are matched one entry at a time, and a name [held] already holds
 * is not new (#402).
 */
internal class BatchOutput(
    private val stream: InputStream,
    private val patterns: TokenPatterns,
    private val window: Int = WINDOW,
    private val held: NamesHeld = NamesHeld.NONE,
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
        if (asked.type == TREE) return NameSearch(patterns, held, asked)
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

/** Text searched a window at a time, each window repeating the end of the one before, so nothing across a seam is missed. */
private class Stretch(private val patterns: TokenPatterns) {
    private var tail = ""

    /** Whether [text], the next characters of this stretch, completes a match. */
    fun found(text: String): Boolean {
        val joined = tail + text
        if (patterns.foundIn(joined)) return true
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
 * A tree: its entries, each a mode, a space, a name, a NUL and a raw object id (#375). Each name is matched whole,
 * as its bytes alone — a name holds no NUL, so no UTF-16 text of an ASCII character can be in one — and a name
 * [held] already holds is not new: it goes out again, and is handed over as sent (#402). An id is skipped by its
 * length, whatever its bytes.
 *
 * An entry that runs past a window waits, whole, for the next. Only a window that matches somewhere has its names
 * matched; any other is walked for where its last whole entry ends, and the last window of a tree not even that,
 * so a tree of one window that matches nowhere is never taken apart. What no entry ends at the end of the tree is
 * matched as bytes, as #375 read every tree; an entry longer than any name can be is not waited on, and refuses.
 */
private class NameSearch(private val patterns: TokenPatterns, private val held: NamesHeld, asked: GitObject) :
    ContentSearch {
    private val idBytes = asked.id.length / 2
    private var left = asked.size
    private var pending = ""

    override fun next(window: String): SearchOutcome {
        left -= window.length
        val entries = Entries(joined(window), idBytes)
        if (patterns.foundInBytes(entries.text)) return searched(entries)
        if (left == 0L) return SearchOutcome.Clean
        return kept(entries.walked())
    }

    private fun joined(window: String): String {
        if (pending.isEmpty()) return window
        return pending + window
    }

    // Each whole entry's name in turn, the first finding ending the search; then what runs on is kept.
    private fun searched(entries: Entries): SearchOutcome {
        val found = entries.names().map(::searched).firstOrNull { it != SearchOutcome.Clean }
        return found ?: kept(entries)
    }

    private fun searched(name: String): SearchOutcome {
        if (!patterns.foundInBytes(name)) return SearchOutcome.Clean
        val isHeld = held.holds(name) ?: return SearchOutcome.Unsearched
        if (!isHeld) return SearchOutcome.FoundInName
        held.sent(name)
        return SearchOutcome.Clean
    }

    // The entry that runs on, kept for the next window; at the tree's end, what no entry ended, matched as bytes.
    private fun kept(entries: Entries): SearchOutcome {
        pending = entries.text.substring(entries.end)
        if (left == 0L) return leftOver()
        if (pending.length > ENTRY_LIMIT) return SearchOutcome.Unsearched
        return SearchOutcome.Clean
    }

    private fun leftOver(): SearchOutcome =
        SearchOutcome.FoundInName.takeIf { patterns.foundInBytes(pending) } ?: SearchOutcome.Clean

    private companion object {
        /**
         * Longer than an entry can be on any filesystem the records are checked out on, whose names stop at 255
         * bytes: a mode, a name of 64 KiB, a NUL and an id. An entry still running past it is not read.
         */
        const val ENTRY_LIMIT = 64 * 1024
    }
}

/**
 * The whole entries of a tree in [text], from its first character — each a mode, a space, a name, a NUL and
 * [idBytes] bytes of id — and [end], where the first entry that runs past [text] starts.
 */
private class Entries(val text: String, private val idBytes: Int) {
    var end = 0
        private set

    /** Each whole entry's name in turn, [end] moving past each entry as its name is handed over. */
    fun names(): Sequence<String> = generateSequence { nameRange()?.let { text.substring(it.first, it.last + 1) } }

    /** These entries, [end] past every whole one, their names left unread. */
    fun walked(): Entries = apply { while (nameRange() != null) Unit }

    // The next whole entry's name, as where it stands in [text], [end] moved past the entry; null when it runs on.
    private fun nameRange(): IntRange? {
        val space = text.indexOf(' ', end)
        if (space < 0) return null
        val nul = text.indexOf(NUL, space + 1)
        if (nul < 0 || nul + 1 + idBytes > text.length) return null
        end = nul + 1 + idBytes
        return space + 1 until nul
    }

    private companion object {
        const val NUL = '\u0000'
    }
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
