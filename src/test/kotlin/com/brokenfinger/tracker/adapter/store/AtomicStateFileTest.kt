package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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
        AtomicStateFile.under(root, "hints.json").write("{}")

        Files.exists(root.resolve(".ps/hints.json")) shouldBe true
    }

    /**
     * A temporary file is created readable by its owner alone, and the replace hands that to the
     * document. A document someone else made — the owner's `.gitignore` (#360) — keeps what it had.
     */
    @Test
    fun `a replace keeps the permissions of the document it replaces`() {
        assumeTrue(canPlantLinksIn(root), "this test reads POSIX permissions")
        val file = timers().also { it.write("""{"a":1}""") }
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

        timers().write("""{"a":1}""")

        Files.isSymbolicLink(path()) shouldBe false
        permissionsOf(path()) shouldBe "rw-------"
    }

    private fun permissionsOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))

    private fun timers() = AtomicStateFile(path())

    private fun path(): Path = root.resolve("state/timers.json")

    private fun stateDirEntries(): List<String> =
        Files.list(path().parent).use { entries -> entries.map { it.name }.sorted().toList() }
}
