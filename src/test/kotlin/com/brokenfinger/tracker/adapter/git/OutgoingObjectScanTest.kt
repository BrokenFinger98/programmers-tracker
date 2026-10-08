package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aFineGrainedShapedToken
import com.brokenfinger.tracker.support.fixtures.aGithubShapedToken
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The push half of the content gate, over **real repositories** (#373): what `rev-list --objects` names
 * is read once, by `cat-file`, and searched; and a scan that cannot read everything refuses. The failures
 * are git's own where git can be made to fail — a missing tree, an object that will not inflate, a call
 * that does not finish, output cut short. Where it cannot, an object is deleted between two calls, or
 * git's real answer is altered. Token-shaped strings are built at runtime, never written out.
 */
class OutgoingObjectScanTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
        committed("README.md", "# records\n")
    }

    // What is found ------------------------------------------------------------------------------------

    @Test
    fun `a history that holds no token is clean`() {
        committed("notes/a.md", "a note\n")
        committed("notes/b.md", "another\n")

        scanned() shouldBe SearchOutcome.Clean
    }

    /** The review's middle commit: a token committed, then deleted. Its blob is still in what goes. */
    @Test
    fun `a token only in a middle commit, deleted since, is found`() {
        committed("notes/pasted.md", "${aGithubShapedToken()}\n")
        repo.git("rm", "--quiet", "notes/pasted.md")
        repo.git("commit", "--message", "removed")

        scanned() shouldBe SearchOutcome.FoundInContent
    }

    @Test
    fun `a fine-grained token is found`() {
        committed("notes/pasted.md", "${aFineGrainedShapedToken()}\n")

        scanned() shouldBe SearchOutcome.FoundInContent
    }

    @Test
    fun `the stored value is found, and only with it stored`() {
        committed("notes/pasted.md", "my token is $A_PUSH_CREDENTIAL\n")

        scanned(stored = StoredCredential.of("$A_PUSH_TOKEN_LINE\n")) shouldBe SearchOutcome.FoundInContent
        scanned(stored = StoredCredential.None) shouldBe SearchOutcome.Clean
    }

    /** `git grep` searches a binary file too, and so does this: a byte is a character, NULs and all. */
    @Test
    fun `a token in a binary blob is found`() {
        val binary = byteArrayOf(0, -1, 0, 0x7f) + aGithubShapedToken().toByteArray() + ByteArray(8)
        committedBytes("assets/blob.bin", binary)

        scanned() shouldBe SearchOutcome.FoundInContent
    }

    /**
     * #372's review: in a UTF-8 locale on macOS, `git grep -E` missed a token beside a Latin-1 byte, an
     * overlong sequence or a lone continuation byte, and exited as if it had found nothing. Read as bytes,
     * each is one character beside a whole token.
     */
    @ParameterizedTest
    @ValueSource(strings = ["e9", "c0af", "80"])
    fun `a token beside an invalid UTF-8 sequence is found`(hex: String) {
        val beside = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        committedBytes("notes/before.md", beside + aGithubShapedToken().toByteArray())
        committedBytes("notes/after.md", aFineGrainedShapedToken().toByteArray() + beside)

        scanned(range = listOf("HEAD~1", "--not", "HEAD~2")) shouldBe SearchOutcome.FoundInContent
        scanned(range = listOf("HEAD", "--not", "HEAD~1")) shouldBe SearchOutcome.FoundInContent
    }

    /** What Windows PowerShell 5.1 writes with `>`: UTF-16LE behind a byte order mark. `git grep` finds nothing. */
    @Test
    fun `a token in a UTF-16 file is found`() {
        val text = byteArrayOf(-1, -2) + "${aGithubShapedToken()}\r\n".toByteArray(Charsets.UTF_16LE)
        committedBytes("notes/powershell.txt", text)

        scanned() shouldBe SearchOutcome.FoundInContent
    }

    // Commits, which hold a message, an author and a committer (#375) -------------------------------------

    /** N6 in #360's review: a token pasted into a commit message went out, since only files were read. */
    @Test
    fun `a token in a commit message is found in that commit's message`() {
        committed("notes/today.md", "a note\n", message = "note: ${aGithubShapedToken()}")
        val commit = repo.git("rev-parse", "HEAD").trim()

        scanned() shouldBe SearchOutcome.FoundInCommit(commit, CommitPart.MESSAGE)
    }

    @Test
    fun `the stored value in a commit message is found`() {
        committed("notes/today.md", "a note\n", message = "remember $A_PUSH_CREDENTIAL")
        val commit = repo.git("rev-parse", "HEAD").trim()

        scanned(stored = StoredCredential.of("$A_PUSH_TOKEN_LINE\n")) shouldBe
            SearchOutcome.FoundInCommit(commit, CommitPart.MESSAGE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["author", "committer"])
    fun `a token in a commit's author or committer is found in that commit's header`(who: String) {
        repo.write("notes/today.md", "a note\n")
        repo.git("add", "--all")
        repo.git(*commitWithA(who, aGithubShapedToken()).toTypedArray())
        val commit = repo.git("rev-parse", "HEAD").trim()

        scanned() shouldBe SearchOutcome.FoundInCommit(commit, CommitPart.HEADER)
    }

    // What is read, and how often ------------------------------------------------------------------------

    /** `git grep` over each commit read an unchanged file once per commit; here a blob is read once. */
    @Test
    fun `a blob many commits share is read once`() {
        committed("notes/shared.md", "the same in every commit\n")
        committed("notes/copy.md", "the same in every commit\n")
        repeat(COMMITS) { committed("notes/$it.md", "note $it\n") }
        val shared = repo.git("rev-parse", "HEAD:notes/shared.md").trim()
        val calls = RecordingCalls(repo.root)

        scanned(calls = calls) shouldBe SearchOutcome.Clean

        calls.read().count { it == shared } shouldBe 1
    }

    @Test
    fun `blobs and commits are read, never trees`() {
        repeat(COMMITS) { committed("notes/$it.md", "note $it\n") }
        val calls = RecordingCalls(repo.root)

        scanned(calls = calls)

        calls.read() shouldContainExactlyInAnyOrder objectsIn(listOf("HEAD"), setOf("blob", "commit"))
    }

    @Test
    fun `objects the range leaves out are not read`() {
        committed("notes/pasted.md", "${aGithubShapedToken()}\n")
        val pushed = repo.git("rev-parse", "HEAD:notes/pasted.md").trim()
        committed("notes/today.md", "a note\n")
        val calls = RecordingCalls(repo.root)

        scanned(range = listOf("HEAD", "--not", "HEAD~1"), calls = calls) shouldBe SearchOutcome.Clean

        calls.read() shouldNotContain pushed
    }

    @Test
    fun `a token in what a later call reads is found`() {
        repeat(COMMITS) { committed("notes/$it.md", "note $it\n") }
        committed("notes/pasted.md", "${aGithubShapedToken()}\n")
        val calls = RecordingCalls(repo.root)

        scanned(calls = calls, bytesPerCall = 1) shouldBe SearchOutcome.FoundInContent

        calls.reads shouldBeGreaterThan 1
    }

    @Test
    fun `nothing outgoing is clean, and nothing is read`() {
        val calls = RecordingCalls(repo.root)

        scanned(range = listOf("HEAD", "--not", "HEAD"), calls = calls) shouldBe SearchOutcome.Clean

        calls.reads shouldBe 0
    }

    // More than one listing: the push reads HEAD's tree beside its range (the review of 315f44e) -------

    @Test
    fun `an object only a second listing names is read`() {
        committed("notes/pasted.md", "${aGithubShapedToken()}\n")
        committed("notes/today.md", "a note\n")
        val listings = listOf(listOf("HEAD", "--not", "HEAD~1"), HEAD_TREE)

        scanned(listings = listings) shouldBe SearchOutcome.FoundInContent
    }

    /** On a first push every blob of HEAD's tree is in the range as well, and is read once all the same. */
    @Test
    fun `an object two listings name is read once`() {
        committed("notes/today.md", "a note\n")
        val note = repo.git("rev-parse", "HEAD:notes/today.md").trim()
        val calls = RecordingCalls(repo.root)

        scanned(listings = listOf(listOf("HEAD"), HEAD_TREE), calls = calls) shouldBe SearchOutcome.Clean

        calls.read().count { it == note } shouldBe 1
    }

    @Test
    fun `a second listing that cannot be listed is unsearched`() {
        val listings = listOf(listOf("HEAD"), listOf("refs/heads/no-such-branch"))

        scanned(listings = listings) shouldBe SearchOutcome.Unsearched
    }

    // Failing closed ------------------------------------------------------------------------------------

    @Test
    fun `objects that cannot be listed are unsearched`() {
        committed("notes/today.md", "a note\n")
        deletedObject("HEAD^{tree}")

        scanned() shouldBe SearchOutcome.Unsearched
    }

    /** Every id is answered for, in the order asked: a description that leaves one out is not taken for one. */
    @Test
    fun `a description that leaves an object out is unsearched`() {
        committed("notes/today.md", "a note\n")
        val calls = describing { it.copy(stdout = withoutItsLastLine(it.stdout)) }

        scanned(calls = calls) shouldBe SearchOutcome.Unsearched
    }

    @Test
    fun `a description git exits non-zero on is unsearched, however whole it looks`() {
        committed("notes/today.md", "a note\n")

        scanned(calls = describing { it.copy(code = FATAL) }) shouldBe SearchOutcome.Unsearched
    }

    /** A loose object that will not inflate is listed — `rev-list` asks only that it exists — and told missing. */
    @Test
    fun `an object git cannot describe is unsearched`() {
        committed("notes/today.md", "a note\n")
        overwrittenObject("HEAD:notes/today.md") { "not an object at all".toByteArray() }

        scanned() shouldBe SearchOutcome.Unsearched
    }

    /**
     * Half of a loose object: its header inflates, so its type and size are told, and its content does not,
     * so `cat-file --batch` exits 128 partway through it.
     */
    @Test
    fun `a cat-file that fails is unsearched`() {
        committed("notes/today.md", (1..LONG).joinToString("\n") { "note $it of $LONG" })
        overwrittenObject("HEAD:notes/today.md") { it.copyOf(it.size / 2) }
        val calls = RecordingCalls(repo.root)

        scanned(calls = calls) shouldBe SearchOutcome.Unsearched

        calls.reads shouldBe 1
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a cat-file that does not finish in time is unsearched`() {
        assumeTrue(canPlantLinksIn(base), "this test runs a shell command")
        committed("notes/today.md", "a note\n")
        val stalling = ProcessCalls(GitProcess(repo.root, timeout = Duration.ofSeconds(1))) { args ->
            if (READ in args) listOf("git", "-c", "alias.stall=!sleep 10", "stall") else plain(args)
        }

        scanned(calls = stalling) shouldBe SearchOutcome.Unsearched
    }

    @Test
    fun `an object gone before it is read is unsearched`() {
        committed("notes/today.md", "a note\n")
        val calls = RecordingCalls(repo.root, before = { if (READ in it) deletedObject("HEAD:notes/today.md") })

        scanned(calls = calls) shouldBe SearchOutcome.Unsearched
    }

    /** `head` exits 0, so git's own answer is cut short with nothing to say so but its length. */
    @Test
    fun `output cut short is unsearched`() {
        assumeTrue(canPlantLinksIn(base), "this test runs a shell command")
        committed("notes/today.md", "a note that runs on for a while, ".repeat(LONG))
        val cutting = ProcessCalls(GitProcess(repo.root)) { args ->
            if (READ in args) listOf("git", "-c", CUT_SHORT, "cut") else plain(args)
        }

        scanned(calls = cutting) shouldBe SearchOutcome.Unsearched
    }

    /**
     * [ProcessCalls] over the workspace, recording each `cat-file --batch`'s input — the ids it was asked
     * to read. [before] acts on the arguments of every call first, and [answered] may alter git's real
     * answer to a call that is read whole.
     */
    private class RecordingCalls(
        root: Path,
        private val before: (List<String>) -> Unit = {},
        private val answered: (List<String>, GitResult) -> GitResult = { _, answer -> answer },
    ) : GitCalls {
        private val real = ProcessCalls(GitProcess(root), ::plain)
        private val inputs = mutableListOf<String>()

        val reads: Int get() = inputs.size

        fun read(): List<String> = inputs.flatMap { it.lines() }.filter { it.isNotEmpty() }

        override fun answer(args: List<String>, input: String?): GitResult {
            before(args)
            return answered(args, real.answer(args, input))
        }

        override fun <T : Any> streamed(args: List<String>, input: String?, read: (InputStream) -> T): T? {
            before(args)
            if (READ in args) inputs += input.orEmpty()
            return real.streamed(args, input, read)
        }
    }

    /** Git's real answers, with its description of the objects — `cat-file --batch-check` — altered by [change]. */
    private fun describing(change: (GitResult) -> GitResult): GitCalls =
        RecordingCalls(repo.root, answered = { args, answer -> if (DESCRIBE in args) change(answer) else answer })

    private fun withoutItsLastLine(text: String): String = text.trimEnd().lines().dropLast(1).joinToString("\n")

    private fun scanned(
        range: List<String> = listOf("HEAD"),
        stored: StoredCredential = StoredCredential.None,
        calls: GitCalls = RecordingCalls(repo.root),
        bytesPerCall: Long = OutgoingObjectScan.BYTES_PER_CALL,
        listings: List<List<String>> = listOf(range),
    ): SearchOutcome = OutgoingObjectScan(calls, bytesPerCall).outcome(listings, stored)

    private fun committed(relative: String, content: String, message: String = "add $relative") =
        committedBytes(relative, content.toByteArray(), message)

    private fun committedBytes(relative: String, content: ByteArray, message: String = "add $relative") {
        val file = repo.root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, content)
        repo.git("add", "--", relative)
        repo.git("commit", "--quiet", "--message", message)
    }

    /** A commit whose author, or whose committer, is named [name]; the other is the workspace's own. */
    private fun commitWithA(who: String, name: String): List<String> {
        if (who == "author") return listOf("commit", "--quiet", "--author", "$name <a@example.invalid>", "-m", "a note")
        return listOf("-c", "user.name=$name", "commit", "--quiet", "--author", AN_AUTHOR, "-m", "a note")
    }

    /** Every object of [types] that [revisions] reach, as git itself lists and types them. */
    private fun objectsIn(revisions: List<String>, types: Set<String>): List<String> {
        val ids = repo.git(*(listOf("rev-list", "--objects") + revisions).toTypedArray()).lines()
            .filter { it.isNotBlank() }.map { it.substringBefore(' ') }
        return ids.filter { repo.git("cat-file", "-t", it).trim() in types }
    }

    /** [revision]'s loose object, deleted; it is read-only, as git writes it, which Windows will not delete. */
    private fun deletedObject(revision: String) {
        val file = looseObjectOf(revision)
        file.toFile().setWritable(true)
        Files.delete(file)
    }

    private fun overwrittenObject(revision: String, change: (ByteArray) -> ByteArray) {
        val file = looseObjectOf(revision)
        file.toFile().setWritable(true)
        Files.write(file, change(Files.readAllBytes(file)))
    }

    private fun looseObjectOf(revision: String): Path {
        val id = repo.git("rev-parse", revision).trim()
        return repo.root.resolve(".git/objects/${id.take(2)}/${id.drop(2)}")
    }

    private companion object {
        /** The argument that makes a call a read of content rather than a question about type and size. */
        const val READ = "--batch"

        /** The author of a commit whose committer carries the token. */
        const val AN_AUTHOR = "Tracker Test <test@example.invalid>"

        /** HEAD's tree as the push lists it beside its range. */
        val HEAD_TREE = listOf("HEAD^{tree}")

        /** The argument that makes a call a question about each object's type and size. */
        const val DESCRIBE = "--batch-check"

        /** How git exits when it dies. */
        const val FATAL = 128

        /** `cat-file --batch` with its answer cut at 60 bytes, by a `head` that exits 0. */
        const val CUT_SHORT = "alias.cut=!git cat-file --batch | head -c 60"

        /** A git call as the tracker makes one with no credential stored: `git` and the arguments. */
        fun plain(args: List<String>): List<String> = listOf("git") + args

        const val COMMITS = 12

        /** Lines of a note long enough that its compressed content runs well past its header. */
        const val LONG = 200
    }
}
