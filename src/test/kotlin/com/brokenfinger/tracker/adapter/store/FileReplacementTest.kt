package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.namesIn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The one write the records repository and its state directory share (#386): a whole file written beside its target
 * and moved over it, in one of three modes. `RecordWritesTest` and `AtomicStateFileTest` show what each caller adds;
 * this is the write itself.
 */
class FileReplacementTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private val target: Path get() = root.resolve("README.md")

    @Test
    fun `replaces a file whole, leaving nothing beside it`() {
        replacing(FileMode.KEPT_ELSE_PLAIN).replace(target, "first, and the longer of the two\n")

        replacing(FileMode.KEPT_ELSE_PLAIN).replace(target, "second\n")

        Files.readString(target) shouldBe "second\n"
        namesIn(root) shouldContainExactly listOf("README.md")
    }

    @Test
    fun `the temporary file is made beside its target, named after it`() {
        val temp = replacing(FileMode.OWNER_ONLY).temporaryBeside(target)

        temp.parent shouldBe target.toAbsolutePath().parent
        temp.fileName.toString() shouldStartWith ".README.md."
        temp.fileName.toString() shouldEndWith FileReplacement.TEMP_SUFFIX
    }

    // The three modes, from the moment the temporary file is made ---------------------------------

    /** What must be owner-only never sits in a wider file first: the temporary file is made that way. */
    @Test
    fun `an owner-only temporary file is owner-only from the moment it is made`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")

        permissionsOf(replacing(FileMode.OWNER_ONLY).temporaryBeside(target)) shouldBe "rw-------"
        permissionsOf(replacing(FileMode.KEPT_ELSE_OWNER_ONLY).temporaryBeside(target)) shouldBe "rw-------"
    }

    @Test
    fun `a record file's temporary file is made as a plain write makes a file`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        val plain = Files.writeString(outside.resolve("plain.md"), "plain\n")

        permissionsOf(replacing(FileMode.KEPT_ELSE_PLAIN).temporaryBeside(target)) shouldBe permissionsOf(plain)
    }

    @Test
    fun `owner-only is kept whatever someone widened the file it replaces to`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        Files.writeString(target, "old\n")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw-rw-"))

        replacing(FileMode.OWNER_ONLY).replace(target, "new\n")

        permissionsOf(target) shouldBe "rw-------"
    }

    @Test
    fun `a kept mode is the regular file's own, in both modes that keep one`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        listOf(FileMode.KEPT_ELSE_PLAIN, FileMode.KEPT_ELSE_OWNER_ONLY).forEach { mode ->
            Files.writeString(target, "old\n")
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw-r--"))

            replacing(mode).replace(target, "new\n")

            permissionsOf(target) shouldBe "rw-rw-r--"
        }
    }

    /** A link's own bits say nothing about a file, so a replaced link keeps none of them. */
    @Test
    fun `a link replaced keeps none of its own mode`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(target, aFileNotOurs(outside))

        replacing(FileMode.KEPT_ELSE_OWNER_ONLY).replace(target, "new\n")

        permissionsOf(target) shouldBe "rw-------"
    }

    // Whatever stands at the target is replaced, never written through ----------------------------

    @Test
    fun `a link at the target is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        aLink(target, elsewhere)

        replacing(FileMode.KEPT_ELSE_PLAIN).replace(target, "new\n")

        Files.readString(elsewhere) shouldBe NOT_OURS
        Files.isSymbolicLink(target) shouldBe false
        Files.readString(target) shouldBe "new\n"
    }

    @Test
    fun `a dangling link at the target is replaced, and nothing is made where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-replace.md")
        aLink(target, nowhere)

        replacing(FileMode.OWNER_ONLY).replace(target, "new\n")

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.readString(target) shouldBe "new\n"
    }

    @Test
    fun `a second name for the file replaced keeps what it held`() {
        Files.writeString(target, "old\n")
        val secondName = outside.resolve("second-name.md")
        assumeTrue(runCatching { Files.createLink(secondName, target) }.isSuccess, "no hard link between the two")

        replacing(FileMode.KEPT_ELSE_PLAIN).replace(target, "new\n")

        Files.readString(secondName) shouldBe "old\n"
    }

    @Test
    fun `an empty directory at the target is replaced`() {
        Files.createDirectory(target)

        replacing(FileMode.OWNER_ONLY).replace(target, "new\n")

        Files.readString(target) shouldBe "new\n"
    }

    /** A move that fails is the filesystem's own failure, and takes the temporary file away with it. */
    @Test
    fun `a directory holding something at the target fails the move, and nothing is left beside it`() {
        Files.writeString(Files.createDirectory(target).resolve("inside.md"), "inside\n")

        val failure = shouldThrow<IOException> { replacing(FileMode.OWNER_ONLY).replace(target, "new\n") }

        failure.shouldBeInstanceOf<FileSystemException>()
        namesIn(root) shouldContainExactly listOf("README.md")
    }

    @Test
    fun `a file is written as UTF-8`() {
        replacing(FileMode.OWNER_ONLY).replace(target, "# 두 수의 곱\n")

        Files.readAllBytes(target).decodeToString() shouldBe "# 두 수의 곱\n"
    }

    private fun replacing(mode: FileMode) = FileReplacement(mode)

    private fun permissionsOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))
}
