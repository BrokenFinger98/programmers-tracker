package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.aFineGrainedShapedToken
import com.brokenfinger.tracker.support.fixtures.aGithubShapedToken
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.text.Charsets.UTF_16LE

/**
 * `cat-file --batch` output read against the objects it was asked for (#373) — unit tests over bytes,
 * no git. Windows are [WINDOW] bytes here, so a seam is easy to put a token across; the one that ships
 * holds a megabyte. Token-shaped strings are built at runtime, never written out.
 */
class BatchOutputTest {
    private val nothingStored = TokenPatterns.of(StoredCredential.None)

    @Test
    fun `objects that hold nothing are clean`() {
        searched(blob(1, "a note\n"), blob(2, "another\n")) shouldBe SearchOutcome.Clean
    }

    @Test
    fun `an empty object is clean`() {
        searched(blob(1, "")) shouldBe SearchOutcome.Clean
    }

    @Test
    fun `a token in any object is found`() {
        searched(blob(1, "a note\n"), blob(2, "pasted ${aGithubShapedToken()}\n")) shouldBe SearchOutcome.FoundInContent
    }

    /** `git grep` searches a binary file too; its bytes are read one character each, NULs and all. */
    @Test
    fun `a token in binary content is found`() {
        val binary = byteArrayOf(0, -1, 0x7f, 0) + aGithubShapedToken().toByteArray() + byteArrayOf(0, -2)

        searched(blob(1, binary)) shouldBe SearchOutcome.FoundInContent
    }

    @Test
    fun `a stored value is found`() {
        val stored = TokenPatterns.of(StoredCredential.of("https://x-access-token:$A_PUSH_CREDENTIAL@github.com\n"))

        searched(blob(1, "my token is $A_PUSH_CREDENTIAL\n"), patterns = stored) shouldBe SearchOutcome.FoundInContent
    }

    // Across a seam: the match ends one byte into a window ---------------------------------------------

    @Test
    fun `the shortest classic token across a seam is found`() {
        val shortest = aGithubShapedToken().toByteArray()

        searched(blob(1, endingOneByteIntoAWindow(shortest))) shouldBe SearchOutcome.FoundInContent
    }

    /** The longest shortest match is what a window repeats, so this is the one an overlap a byte short misses. */
    @Test
    fun `the shortest fine-grained token across a seam is found`() {
        val shortest = aFineGrainedShapedToken().take(TokenPatterns.LARGEST_MINIMUM_MATCH).toByteArray()

        searched(blob(1, endingOneByteIntoAWindow(shortest))) shouldBe SearchOutcome.FoundInContent
    }

    /** In UTF-16 the same token takes twice the bytes, and the window repeats that much. */
    @Test
    fun `the shortest fine-grained token in UTF-16 across a seam is found`() {
        val shortest = aFineGrainedShapedToken().take(TokenPatterns.LARGEST_MINIMUM_MATCH).toByteArray(UTF_16LE)

        searched(blob(1, endingOneByteIntoAWindow(shortest))) shouldBe SearchOutcome.FoundInContent
    }

    @Test
    fun `a stored value longer than every shape across a seam is found`() {
        val line = "https://x-access-token:${"n".repeat(LONGER_THAN_A_SHAPE)}@github.com"
        val stored = TokenPatterns.of(StoredCredential.of("$line\n"))
        val printed = blob(1, endingOneByteIntoAWindow(line.toByteArray()))

        searched(printed, patterns = stored) shouldBe SearchOutcome.FoundInContent
    }

    /** Each object is searched apart, as `git grep` searches each file: a token is never made of two. */
    @Test
    fun `a token is never read from one object into the next`() {
        val token = aGithubShapedToken()

        searched(blob(1, token.take(HALF)), blob(2, token.drop(HALF))) shouldBe SearchOutcome.Clean
    }

    // Commits: a match is said as the part of the commit it is in (#375) ---------------------------------

    @Test
    fun `a token in a commit message is found in that commit's message`() {
        val printed = commit(1, headerOfLength(SHORT_HEADER) + "\nnote: ${aGithubShapedToken()}\n")

        searched(printed) shouldBe SearchOutcome.FoundInCommit(idOf(1), CommitPart.MESSAGE)
    }

    @Test
    fun `a token in a commit's author is found in that commit's header`() {
        val printed = commit(1, "tree ${"0".repeat(ID_LENGTH)}\nauthor ${aGithubShapedToken()} <a@b.invalid>\n\nnote\n")

        searched(printed) shouldBe SearchOutcome.FoundInCommit(idOf(1), CommitPart.HEADER)
    }

    @Test
    fun `a commit that holds no token is clean`() {
        searched(commit(1, headerOfLength(SHORT_HEADER) + "\na note\n\nwith a second paragraph\n")) shouldBe
            SearchOutcome.Clean
    }

    /** The empty line that ends the header falls across a seam: its first newline is a window's last byte. */
    @Test
    fun `a header that ends on a seam ends there`() {
        val printed = commit(1, headerOfLength(WINDOW) + "\nnote: ${aGithubShapedToken()}\n")

        searched(printed) shouldBe SearchOutcome.FoundInCommit(idOf(1), CommitPart.MESSAGE)
    }

    @Test
    fun `a token across a seam in a commit's header is found there`() {
        val author = "a".repeat(2 * WINDOW + 1 - CLASSIC - HEADER_START) + aGithubShapedToken()
        val printed = commit(1, "tree ${"0".repeat(ID_LENGTH)}\nauthor $author <a@b.invalid>\n\nnote\n")

        searched(printed) shouldBe SearchOutcome.FoundInCommit(idOf(1), CommitPart.HEADER)
    }

    @Test
    fun `a token across a seam in a commit message is found there`() {
        val filler = ".".repeat(3 * WINDOW + 1 - CLASSIC - (WINDOW + 1))
        val printed = commit(1, headerOfLength(WINDOW) + "\n" + filler + aGithubShapedToken() + "\n")

        searched(printed) shouldBe SearchOutcome.FoundInCommit(idOf(1), CommitPart.MESSAGE)
    }

    // Trees: what is found in one is found in a name (#375) ----------------------------------------------

    @Test
    fun `a token in a tree's entry name is found in a name`() {
        val printed = tree(1, entry("100644", "${aGithubShapedToken()}.md"), entry("40000", "notes"))

        searched(printed) shouldBe SearchOutcome.FoundInName
    }

    /** A name cannot hold a NUL, so a tree is read as bytes alone: UTF-16 bytes in one are never a token. */
    @Test
    fun `a tree is read as its bytes alone`() {
        val printed = tree(1, entry("100644", "a.md"), "note ${aGithubShapedToken()}".toByteArray(UTF_16LE))

        searched(printed) shouldBe SearchOutcome.Clean
    }

    /**
     * The id after a name is 20 raw bytes: a run of token characters in it ends at the NUL before it and at the
     * space after the next entry's mode, 26 bytes at most, and a token needs 40. Here the id is all letters.
     */
    @Test
    fun `an object id never completes a token`() {
        val letters = ByteArray(ID_BYTES) { 'A'.code.toByte() }
        val printed = tree(1, entry("100644", "ghp_", letters), entry("100644", "b.md"))

        searched(printed) shouldBe SearchOutcome.Clean
    }

    // Names a destination already holds (#402) -----------------------------------------------------------

    /**
     * A tree carries every name in its directory, so a new file beside a name the destination already holds sends
     * that name again. It is not new: not refused, and handed over as sent again, for the notice.
     */
    @Test
    fun `a name the destination already holds is not new, and is sent again`() {
        val held = Held("${aGithubShapedToken()}.md")
        val printed = tree(1, entry("100644", "${aGithubShapedToken()}.md"), entry("100644", "today.md"))

        searched(printed, held = held) shouldBe SearchOutcome.Clean
        held.sent shouldBe listOf(asBytes("${aGithubShapedToken()}.md"))
    }

    @Test
    fun `a new name beside one the destination holds is found`() {
        val held = Held("${aGithubShapedToken()}.md")
        val beside = entry("100644", aGithubShapedToken('B'))
        val printed = tree(1, entry("100644", "${aGithubShapedToken()}.md"), beside)

        searched(printed, held = held) shouldBe SearchOutcome.FoundInName
    }

    /** What is held is a name, whole: a name that holds a held one and more is a new name. */
    @Test
    fun `a name that holds a held one and more is new`() {
        val printed = tree(1, entry("100644", "copy of ${aGithubShapedToken()}.md"))

        searched(printed, held = Held("${aGithubShapedToken()}.md")) shouldBe SearchOutcome.FoundInName
    }

    /** Whether a name is held costs a listing of the destination's trees, so only a name that matches is asked about. */
    @Test
    fun `only a name that carries a token is asked about`() {
        val held = Held()
        val token = entry("100644", "${aGithubShapedToken()}.md")
        val printed = tree(1, entry("100644", "a.md"), token, entry("40000", "notes"))

        searched(printed, held = held)

        held.asked shouldBe listOf(asBytes("${aGithubShapedToken()}.md"))
    }

    @Test
    fun `a name the destination cannot say it holds is unsearched`() {
        val printed = tree(1, entry("100644", "${aGithubShapedToken()}.md"))

        searched(printed, held = Unknown) shouldBe SearchOutcome.Unsearched
    }

    /** An entry that runs past a window waits for the rest, so its name is matched whole: held, or new. */
    @Test
    fun `a name across a seam is matched whole`() {
        val name = "n".repeat(WINDOW - HALF) + "${aGithubShapedToken()}.md"
        val printed = tree(1, entry("100644", "a.md"), entry("100644", name))

        searched(printed, held = Held(name)) shouldBe SearchOutcome.Clean
        searched(printed, held = Held()) shouldBe SearchOutcome.FoundInName
    }

    /** An id is skipped by its length, whatever its bytes: a space or a NUL in one keeps the entries in step. */
    @Test
    fun `an id that holds a space or a NUL keeps the entries in step`() {
        val awkward = ByteArray(ID_BYTES) { if (it % 2 == 0) ' '.code.toByte() else 0 }
        val printed = tree(1, entry("100644", "a.md", awkward), entry("100644", "${aGithubShapedToken()}.md"))

        searched(printed, held = Held("${aGithubShapedToken()}.md")) shouldBe SearchOutcome.Clean
    }

    /** A repository that names objects with SHA-256 stores 32-byte ids in its trees, and they are skipped whole. */
    @Test
    fun `a tree whose ids are SHA-256 is read in step`() {
        val spaced = ByteArray(SHA_256_ID_BYTES) { if (it == ID_BYTES + 2) ' '.code.toByte() else 'x'.code.toByte() }
        val entries = entry("100644", "a.md", spaced) + entry("100644", "${aGithubShapedToken()}.md", spaced)
        val printed = Printed(GitObject("e".repeat(SHA_256_ID_LENGTH), "tree", entries.size.toLong()), entries)

        searched(printed, held = Held("${aGithubShapedToken()}.md")) shouldBe SearchOutcome.Clean
    }

    /** What no entry ends, at the end of a tree, is matched as bytes, as #375 read every tree, and never held. */
    @Test
    fun `what runs past a tree's last entry is matched as bytes`() {
        val printed = tree(1, entry("100644", "a.md"), "100644 ${aGithubShapedToken()}".toByteArray())

        searched(printed, held = Held(aGithubShapedToken())) shouldBe SearchOutcome.FoundInName
    }

    /** A name no filesystem could hold would be waited on without end: it is not read, and nothing goes out. */
    @Test
    fun `an entry longer than any name can be is unsearched`() {
        val printed = tree(1, entry("100644", "n".repeat(LONGER_THAN_ANY_NAME)))

        searched(printed) shouldBe SearchOutcome.Unsearched
    }

    // Output that is not what was asked for -------------------------------------------------------------

    @Test
    fun `an object reported missing is unsearched`() {
        val asked = GitObject(idOf(1), "blob", 6)

        searched(listOf(asked), "${idOf(1)} missing\n".toByteArray()) shouldBe SearchOutcome.Unsearched
    }

    @Test
    fun `a header for another object is unsearched`() {
        val printed = blob(2, "a note")

        searched(listOf(GitObject(idOf(1), "blob", 6)), printed.bytes) shouldBe SearchOutcome.Unsearched
    }

    @Test
    fun `a header of another type is unsearched`() {
        val printed = Printed(GitObject(idOf(1), "tree", 6), "a note".toByteArray())

        searched(listOf(GitObject(idOf(1), "blob", 6)), printed.bytes) shouldBe SearchOutcome.Unsearched
    }

    @Test
    fun `a header of another size is unsearched`() {
        val printed = blob(1, "a note")

        searched(listOf(GitObject(idOf(1), "blob", 7)), printed.bytes) shouldBe SearchOutcome.Unsearched
    }

    @Test
    fun `content that ends early is unsearched`() {
        val printed = blob(1, "a note that git stopped printing")

        searched(listOf(printed.asked), printed.bytes.copyOf(printed.bytes.size - 10)) shouldBe SearchOutcome.Unsearched
    }

    @Test
    fun `content without the newline after it is unsearched`() {
        val printed = blob(1, "a note")

        searched(listOf(printed.asked), printed.bytes.copyOf(printed.bytes.size - 1) + 'x'.code.toByte()) shouldBe
            SearchOutcome.Unsearched
    }

    @Test
    fun `output left over after the last object is unsearched`() {
        val printed = blob(1, "a note")

        searched(listOf(printed.asked), printed.bytes + "${idOf(2)} blob 0\n\n".toByteArray()) shouldBe
            SearchOutcome.Unsearched
    }

    @Test
    fun `output that ends before an object is unsearched`() {
        val printed = blob(1, "a note")

        searched(listOf(printed.asked, GitObject(idOf(2), "blob", 6)), printed.bytes) shouldBe SearchOutcome.Unsearched
    }

    /** A header is a short line; one that never ends is read no further than a header can be long. */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a header that never ends is not read without end`() {
        val endless = object : InputStream() {
            override fun read(): Int = 'x'.code
        }

        BatchOutput(endless, nothingStored, WINDOW).searched(listOf(GitObject(idOf(1), "blob", 6))) shouldBe
            SearchOutcome.Unsearched
    }

    /** What `cat-file --batch` prints for [asked]: its header, its content, and a newline. */
    private class Printed(val asked: GitObject, content: ByteArray) {
        val bytes: ByteArray = "${asked.header()}\n".toByteArray() + content + "\n".toByteArray()
    }

    private fun blob(n: Int, content: String): Printed = blob(n, content.toByteArray())

    private fun commit(n: Int, content: String): Printed =
        content.toByteArray().let { Printed(GitObject(idOf(n), "commit", it.size.toLong()), it) }

    private fun tree(n: Int, vararg entries: ByteArray): Printed =
        entries.fold(ByteArray(0)) { all, each -> all + each }
            .let { Printed(GitObject(idOf(n), "tree", it.size.toLong()), it) }

    /** One tree entry as git stores it: its mode, a space, its name, a NUL, and a 20-byte object id. */
    private fun entry(mode: String, name: String, id: ByteArray = ByteArray(ID_BYTES) { 7 }): ByteArray =
        "$mode $name".toByteArray() + byteArrayOf(0) + id

    /**
     * A commit's header, [length] characters with the newline that ends it: a tree line, then an author whose
     * name fills it out. The empty line that ends the header is the next character, the message's own.
     */
    private fun headerOfLength(length: Int): String =
        "tree ${"0".repeat(ID_LENGTH)}\nauthor " + "a".repeat(length - HEADER_START - 1) + "\n"

    private fun blob(n: Int, content: ByteArray): Printed =
        Printed(GitObject(idOf(n), "blob", content.size.toLong()), content)

    private fun searched(
        vararg printed: Printed,
        patterns: TokenPatterns = nothingStored,
        held: NamesHeld = NamesHeld.NONE,
    ): SearchOutcome {
        val output = printed.fold(ByteArray(0)) { all, each -> all + each.bytes }
        return BatchOutput(ByteArrayInputStream(output), patterns, WINDOW, held).searched(printed.map { it.asked })
    }

    private fun searched(asked: List<GitObject>, output: ByteArray, patterns: TokenPatterns = nothingStored) =
        BatchOutput(ByteArrayInputStream(output), patterns, WINDOW).searched(asked)

    /** A destination that holds [names], each as its bytes, and remembers each name asked about, and each sent. */
    private class Held(vararg names: String) : NamesHeld {
        private val held = names.map(::asBytes).toSet()
        val asked = mutableListOf<String>()
        val sent = mutableListOf<String>()

        override fun holds(name: String): Boolean {
            asked += name
            return name in held
        }

        override fun sent(name: String) {
            sent += name
        }
    }

    /** A destination that cannot say what it holds, as one whose trees git could not list. */
    private object Unknown : NamesHeld {
        override fun holds(name: String): Boolean? = null
    }

    /** [match] behind filler, placed so that its last byte is the first byte of a window. */
    private fun endingOneByteIntoAWindow(match: ByteArray): ByteArray {
        val windows = match.size / WINDOW + 2
        val filler = ByteArray(windows * WINDOW + 1 - match.size) { '.'.code.toByte() }
        return filler + match + ByteArray(WINDOW) { '.'.code.toByte() }
    }

    private fun idOf(n: Int): String = n.toString().padStart(ID_LENGTH, '0')

    private companion object {
        const val WINDOW = 64
        const val ID_LENGTH = 40
        const val HALF = 20
        const val LONGER_THAN_A_SHAPE = 200

        /** `ghp_` and 36 characters, as [aGithubShapedToken] builds it. */
        const val CLASSIC = 40

        /** Where an author's name starts in a commit: after `tree <40 zeros>\n` and `author `. */
        const val HEADER_START = 53

        /** A header well inside one window. */
        const val SHORT_HEADER = 60

        /** A SHA-1 object id as a tree stores it, raw. */
        const val ID_BYTES = 20

        /** A SHA-256 object id, raw as a tree stores it, and in hex as git prints it. */
        const val SHA_256_ID_BYTES = 32
        const val SHA_256_ID_LENGTH = 64

        /** Past what any filesystem lets a name be, 255 bytes, and past what the search waits for. */
        const val LONGER_THAN_ANY_NAME = 1 shl 17

        /** A name as a tree holds it: its UTF-8 bytes, one ISO-8859-1 character each, as they are read. */
        fun asBytes(name: String): String = String(name.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    }
}
