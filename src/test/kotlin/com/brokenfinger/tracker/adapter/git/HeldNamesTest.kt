package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The names a destination already holds (#402), from the trees of the tips it said it holds, over **real git**.
 * A name is an entry's name at any depth — a file's, a directory's — held as its bytes, wherever it stands.
 */
class HeldNamesTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
        repo.write("notes/a.md", "a\n")
        repo.write("$SPACED_HANGUL/deep/b.md", "b\n")
        repo.git("add", "--all")
        repo.git("commit", "--message", "init")
    }

    @Test
    fun `every name a tip's tree holds, at any depth, is held`() {
        val held = heldAt("HEAD")

        listOf("notes", "a.md", SPACED_HANGUL, "deep", "b.md").forEach { held.holds(asBytes(it)) shouldBe true }
    }

    @Test
    fun `a name no tip's tree holds is not held`() {
        heldAt("HEAD").holds("today.md") shouldBe false
    }

    /** Held is a name, whole: a path is not a name, and neither is part of one. */
    @Test
    fun `a path or part of a name is not a name held`() {
        val held = heldAt("HEAD")

        held.holds("notes/a.md") shouldBe false
        held.holds("a") shouldBe false
    }

    /** A remote lists its tags with its branches; an annotated tag is a tip, peeled to its commit's tree. */
    @Test
    fun `an annotated tag's tree is held`() {
        repo.git("config", "tag.gpgSign", "false")
        repo.git("tag", "--annotate", "v1", "--message", "v1")
        val tag = repo.git("rev-parse", "v1").trim()
        repo.git("rm", "--quiet", "-r", "notes")
        repo.git("commit", "--message", "notes removed")

        held(setOf(tag)).holds("a.md") shouldBe true
    }

    /** A tag can name a blob, which holds no name; the other tips still count. */
    @Test
    fun `a tip that holds no tree holds no name, and the rest still count`() {
        val blob = repo.git("rev-parse", "HEAD:notes/a.md").trim()

        held(setOf(blob, head())).holds("a.md") shouldBe true
        held(setOf(blob)).holds("a.md") shouldBe false
    }

    /** Every tip counts: each of two names is held at one tip and not the other, and both are held. */
    @Test
    fun `a name any tip holds is held`() {
        val first = head()
        repo.git("rm", "--quiet", "-r", "notes")
        repo.write("later.md", "later\n")
        repo.git("add", "--all")
        repo.git("commit", "--message", "later")
        val held = held(setOf(first, head()))

        held.holds("a.md") shouldBe true
        held.holds("later.md") shouldBe true
    }

    @Test
    fun `with no tips nothing is held, and git is not asked`() {
        val calls = Counting(repo.root)

        HeldNames(calls, emptySet()).holds("a.md") shouldBe false

        calls.asked shouldBe 0
    }

    /** Listing a destination's trees costs a call per tree, so nothing is listed until a name is asked about. */
    @Test
    fun `nothing is listed until a name is asked about, and then once`() {
        val calls = Counting(repo.root)
        val held = HeldNames(calls, setOf(head()))
        calls.asked shouldBe 0

        repeat(3) { held.holds("a.md") }

        calls.asked shouldBe 2
    }

    @Test
    fun `a tree git cannot list is no answer`() {
        val held = heldAt("HEAD")
        deletedObject("HEAD:notes")

        held.holds("a.md").shouldBeNull()
    }

    @Test
    fun `tips git cannot peel are no answer`() {
        val failing = Counting(repo.root, failing = "cat-file")

        HeldNames(failing, setOf(head())).holds("a.md").shouldBeNull()
    }

    /** The names a search found held, and so sent again, are kept for the notice, in the order sent. */
    @Test
    fun `the names sent again are kept`() {
        val held = heldAt("HEAD")

        held.sent(asBytes("a.md"))
        held.sent(asBytes("b.md"))

        held.sentAgain() shouldContainExactly listOf(asBytes("a.md"), asBytes("b.md"))
    }

    private fun heldAt(revision: String): HeldNames = held(setOf(repo.git("rev-parse", revision).trim()))

    private fun held(tips: Set<String>): HeldNames = HeldNames(Counting(repo.root), tips)

    private fun head(): String = repo.git("rev-parse", "HEAD").trim()

    /** [revision]'s loose object, deleted; git writes it read-only, which Windows will not delete. */
    private fun deletedObject(revision: String) {
        val id = repo.git("rev-parse", revision).trim()
        val file = repo.root.resolve(".git/objects/${id.take(2)}/${id.drop(2)}")
        file.toFile().setWritable(true)
        Files.delete(file)
    }

    /** Real git, its calls counted; a call whose first argument is [failing] answers as git failing would. */
    private class Counting(root: Path, private val failing: String? = null) : GitCalls {
        private val real = ProcessCalls(GitProcess(root)) { listOf("git") + it }
        var asked = 0
            private set

        override fun answer(args: List<String>, input: String?): GitResult {
            asked++
            if (args.first() == failing) return GitResult(FAILED, "", "fatal: as asked")
            return real.answer(args, input)
        }

        override fun <T : Any> streamed(args: List<String>, input: String?, read: (InputStream) -> T): T? {
            asked++
            return real.streamed(args, input, read)
        }
    }

    private companion object {
        /** A directory name with a space and Hangul in it ("note folder"), held as its UTF-8 bytes. */
        const val SPACED_HANGUL = "노트 폴더"

        const val FAILED = 128

        fun asBytes(name: String): String = String(name.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    }
}
