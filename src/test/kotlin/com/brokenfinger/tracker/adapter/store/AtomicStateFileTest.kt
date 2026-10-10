package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.adapter.store.StateDirectory.Refusal
import com.brokenfinger.tracker.support.fixtures.ChangingAnswer
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aListingThatFailsOnce
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.fixtures.sealedWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.name

class AtomicStateFileTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `reading a state file that was never written is null, not an empty document`() {
        timers().read().shouldBeNull()
    }

    @Test
    fun `write creates the state directory and the file`() {
        timers().write("""{"120804":1754400000}""")

        Files.readString(path()) shouldBe """{"120804":1754400000}"""
    }

    @Test
    fun `write replaces the previous document whole`() {
        val file = timers()
        file.write("""{"a":1}""")

        file.write("""{"b":2}""")

        file.read() shouldBe """{"b":2}"""
    }

    @Test
    fun `no temporary file is left behind, so a reader never finds a half-written document`() {
        val file = timers()

        file.write("""{"a":1}""")
        file.write("""{"b":2}""")

        stateDirEntries() shouldContainExactly listOf("timers.json")
    }

    @Test
    fun `the temporary file is created in the target directory, so the move can be atomic`() {
        // A cross-filesystem move degrades to copy-then-delete, which is exactly the
        // torn-read window this helper exists to remove.
        timers().write("""{"a":1}""")

        Files.readString(path()) shouldBe """{"a":1}"""
    }

    @Test
    fun `update sees the current document and writes what the transform returns`() {
        val file = timers()
        file.write("""{"a":1}""")

        file.update { current -> current!!.replace("1", "2") }

        file.read() shouldBe """{"a":2}"""
    }

    @Test
    fun `update on a missing document is handed null rather than an empty string`() {
        timers().update { current ->
            current.shouldBeNull()
            """{"created":true}"""
        }

        timers().read() shouldBe """{"created":true}"""
    }

    @Test
    fun `a transform that throws leaves the previous document intact and no debris behind`() {
        val file = timers()
        file.write("""{"a":1}""")

        shouldThrow<IllegalStateException> { file.update { error("cannot compute") } }

        file.read() shouldBe """{"a":1}"""
        stateDirEntries() shouldContainExactly listOf("timers.json")
    }

    @Test
    fun `under resolves the state file inside the record repository`() {
        AtomicStateFile.under(root, "hints.json", aStateDirectory(root)).write("{}")

        Files.exists(root.resolve(".ps/hints.json")) shouldBe true
    }

    /**
     * State files are the server's own — the timers, the backup marker, the push credential — and
     * every write leaves them readable by their owner alone, whatever someone widened them to in
     * between (#360). The temporary file starts owner-only, and the replace hands that on.
     */
    @Test
    fun `a state file is written owner-only, even after someone widened it`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        val file = timers().also { it.write("""{"a":1}""") }
        Files.setPosixFilePermissions(path(), PosixFilePermissions.fromString("rw-rw-rw-"))

        file.write("""{"b":2}""")

        permissionsOf(path()) shouldBe "rw-------"
    }

    /**
     * A document someone else made — the owner's `.gitignore` (#360) — keeps what it had, when the
     * writer asks for that. Only `RecordRepositoryIgnores` does.
     */
    @Test
    fun `a replace keeps the permissions of the document it replaces, when asked to`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        val file = keeping().also { it.write("""{"a":1}""") }
        Files.setPosixFilePermissions(path(), PosixFilePermissions.fromString("rw-rw-r--"))

        file.write("""{"b":2}""")

        permissionsOf(path()) shouldBe "rw-rw-r--"
    }

    /** Only a regular file's are kept: a link's own bits say nothing about a document, and are often everyone's. */
    @Test
    fun `a document that replaces a link takes none of the link's own permissions`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.writeString(root.resolve("elsewhere.json"), "{}")
        aLink(path(), elsewhere)

        keeping().write("""{"a":1}""")

        Files.isSymbolicLink(path()) shouldBe false
        permissionsOf(path()) shouldBe "rw-------"
    }

    // Under the record repository, only into the real state directory (#360) ------------------

    /**
     * `.ps` swapped for a tracked link into the tree: a state file written through it would be a path
     * git tracks, and once the link was gone a reconciliation committed what had piled up there. So
     * nothing is written, and the skip is said once for the file.
     */
    @Test
    fun `a state file is not written while the state directory is not its own`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        aLink(root.resolve(".ps"), tracked)
        val timers = AtomicStateFile.under(root, "timers.json", aStateDirectory(root))

        val heard = warningsWhile(AtomicStateFile::class) {
            timers.write("""{"a":1}""")
            timers.write("""{"b":2}""")
        }

        Files.list(tracked).use { it.count() } shouldBe 0L
        heard.single() shouldContain "is not the tracker's own state directory"
    }

    /** A file git tracks below `.ps` is a change any `commit -a` publishes, so nothing is written. */
    @Test
    fun `a state file is not written while git tracks something under the state directory`() {
        val timers = AtomicStateFile.under(root, "timers.json", aStateDirectory(root, tracked = { true }))

        val heard = warningsWhile(AtomicStateFile::class) { timers.write("""{"a":1}""") }

        Files.exists(root.resolve(".ps/timers.json")) shouldBe false
        heard.single() shouldContain "git tracks files under .ps"
    }

    /** Git that cannot be asked does not cost a capture its state; it is commits and pushes that refuse. */
    @Test
    fun `a state file is written when git cannot say what it tracks`() {
        val timers = AtomicStateFile.under(root, "timers.json", aStateDirectory(root, tracked = { null }))

        timers.write("""{"a":1}""")

        Files.readString(root.resolve(".ps/timers.json")) shouldBe """{"a":1}"""
    }

    /** A link where the document should be is no document: never read through, and the next write replaces it (#360). */
    @Test
    fun `a link where the document should be reads as none`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.writeString(Files.createDirectories(root.resolve("elsewhere")).resolve("a.json"), "{}")
        aLink(path(), elsewhere)

        timers().read().shouldBeNull()
    }

    /**
     * Nor is a FIFO, and it is never opened (#387's review): opening one to read waits until something writes into it,
     * and a FIFO at the tool's `.ps/watch-token` held the server's start that way. The next write replaces it.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO where the document should be reads as none, without waiting, and is replaced`() {
        Files.createDirectories(path().parent)
        assumeTrue(madeFifo(path()), "this test makes a FIFO")

        timers().read().shouldBeNull()
        timers().write("{}")

        Files.readString(path()) shouldBe "{}"
    }

    /**
     * A document that cannot be looked at is no answer, and is thrown rather than read as none (#387): taken for a
     * document never written, the next write would start from nothing. Pinned from the mutation round, as before.
     */
    @Test
    fun `a document that cannot be looked at is thrown, not read as none`() {
        assumeTrue(keepsPosixPermissions(root), "this test takes a directory's permissions away")
        timers().write("""{"a":1}""")

        sealedWhile(path().parent) {
            assumeTrue(!Files.isReadable(path().parent), "a superuser reads it anyway")
            shouldThrow<AccessDeniedException> { timers().read() }
        }
    }

    /**
     * Said once for each reason, not once for the file: a refusal that passed had used up the only WARN
     * a lasting one would get (the review of ea1357c).
     */
    @Test
    fun `each reason a write is refused for is said`() {
        val git = ChangingAnswer(true)
        val timers = AtomicStateFile.under(
            root,
            "timers.json",
            StateDirectory(root, git, listing = aListingThatFailsOnce()),
        )

        val heard = warningsWhile(AtomicStateFile::class) { repeat(3) { timers.write("{}") } }

        heard.size shouldBe 2
        heard.last() shouldContain "git tracks files under .ps"
    }

    // What a write does to whatever stands at the document's name, pinned before #386 shares the write ------

    @Test
    fun `a link where the document should be is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.writeString(Files.createDirectories(root.resolve("elsewhere")).resolve("a.json"), "{}")
        aLink(path(), elsewhere)

        timers().write("""{"a":1}""")

        Files.readString(elsewhere) shouldBe "{}"
        Files.isSymbolicLink(path()) shouldBe false
        Files.readString(path()) shouldBe """{"a":1}"""
    }

    @Test
    fun `a dangling link where the document should be is replaced, and nothing is created where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = root.resolve("elsewhere/made-by-a-state-file.json")
        aLink(path(), nowhere)

        timers().write("""{"a":1}""")

        Files.exists(nowhere, LinkOption.NOFOLLOW_LINKS) shouldBe false
        Files.readString(path()) shouldBe """{"a":1}"""
    }

    /** Replaced rather than written into, so a second name for the old document keeps what it held. */
    @Test
    fun `a document with a second name is replaced, and the other name keeps its bytes`() {
        timers().write("""{"a":1}""")
        val secondName = root.resolve("second-name.json")
        assumeTrue(runCatching { Files.createLink(secondName, path()) }.isSuccess, "no hard link here")

        timers().write("""{"b":2}""")

        Files.readString(secondName) shouldBe """{"a":1}"""
        Files.readString(path()) shouldBe """{"b":2}"""
    }

    /** The move falls back to replacing what stands there, and an empty directory is something it can replace. */
    @Test
    fun `an empty directory where the document should be is replaced by it`() {
        Files.createDirectories(path())

        timers().write("""{"a":1}""")

        Files.readString(path()) shouldBe """{"a":1}"""
    }

    /** Failed as the filesystem fails it — no refusal of the state file's own — and nothing is left beside it. */
    @Test
    fun `a directory holding something where the document should be fails the write, leaving nothing beside it`() {
        Files.writeString(Files.createDirectories(path()).resolve("inside.json"), "{}")

        val failure = shouldThrow<IOException> { timers().write("""{"a":1}""") }

        failure.shouldBeInstanceOf<FileSystemException>()
        stateDirEntries() shouldContainExactly listOf("timers.json")
        Files.readString(path().resolve("inside.json")) shouldBe "{}"
    }

    @Test
    fun `a document is written as UTF-8`() {
        timers().write("""{"title":"두 수의 곱"}""")

        Files.readAllBytes(path()).decodeToString() shouldBe """{"title":"두 수의 곱"}"""
    }

    /** The words of the one warning this class says, once per reason. */
    @Test
    fun `a state file refused is said in exactly these words`() {
        val timers = AtomicStateFile.under(root, "timers.json", aStateDirectory(root, tracked = { true }))

        val heard = warningsWhile(AtomicStateFile::class) { timers.write("{}") }

        heard.single() shouldBe
            "The state file timers.json was not written: ${Refusal.TRACKED.reason}. Said once for this reason."
    }

    private fun permissionsOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))

    private fun timers() = AtomicStateFile(path())

    private fun keeping() = AtomicStateFile(path(), keepsPermissions = true)

    private fun path(): Path = root.resolve("state/timers.json")

    private fun stateDirEntries(): List<String> =
        Files.list(path().parent).use { entries -> entries.map { it.name }.sorted().toList() }
}
