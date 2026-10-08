package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.namesIn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.nio.file.FileSystems
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

    /**
     * A bare name has no parent of its own — `tracker.watch.token-file=watch-token` is one — so its temporary file is
     * made in the directory the name resolves against, never in the system's temporary directory, from which the
     * move would cross to another filesystem (#386's review). A zip file system stands in for the working directory,
     * which a test does not write into: its own resolves a bare name against `/`.
     */
    @Test
    fun `a bare name's temporary file is made where the name resolves`() {
        FileSystems.newFileSystem(root.resolve("names.zip"), mapOf("create" to "true")).use { zip ->
            val temp = replacing(FileMode.KEPT_ELSE_PLAIN).temporaryBeside(zip.getPath("README.md"))

            temp.fileSystem shouldBe zip
            temp.parent shouldBe zip.getPath("/")
        }
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

    /**
     * A clean-up that fails too never takes the failure's place: what the caller sees is what stopped the write, with
     * the clean-up's own failure kept on it (#386's review). An append-only directory, as `chflags uappnd` makes one on
     * macOS, lets the temporary file be made and refuses both the move and the removal.
     */
    @Test
    fun `a clean-up that fails too is kept with the failure, never thrown in its place`() {
        val directory = Files.createDirectory(root.resolve("append-only"))
        val page = Files.writeString(directory.resolve("README.md"), "before\n")
        assumeTrue(flagged(directory, "uappnd"), "no chflags on this machine")
        try {
            val failure = shouldThrow<IOException> { replacing(FileMode.KEPT_ELSE_PLAIN).replace(page, "after\n") }

            failure.suppressed.single().shouldBeInstanceOf<FileSystemException>()
        } finally {
            flagged(directory, "nouappnd")
        }
        Files.readString(page) shouldBe "before\n"
    }

    @Test
    fun `a file is written as UTF-8`() {
        replacing(FileMode.OWNER_ONLY).replace(target, "# 두 수의 곱\n")

        Files.readAllBytes(target).decodeToString() shouldBe "# 두 수의 곱\n"
    }

    // The same write, through a directory held open (#374) ----------------------------------------

    @Test
    fun `a replace through a held directory replaces the file whole, leaving nothing beside it`() {
        Files.writeString(target, "first, and the longer of the two\n")

        held().use { replacing(FileMode.OWNER_ONLY).replace(it, "README.md", "second\n") }

        Files.readString(target) shouldBe "second\n"
        namesIn(root) shouldContainExactly listOf("README.md")
    }

    @Test
    fun `a replace through a held directory keeps a regular file's mode where its mode says so`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        Files.writeString(target, "old\n")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-rw-r--"))

        held().use { replacing(FileMode.KEPT_ELSE_PLAIN).replace(it, "README.md", "new\n") }

        permissionsOf(target) shouldBe "rw-rw-r--"
    }

    @Test
    fun `a replace through a held directory that fails takes its temporary file away`() {
        Files.writeString(Files.createDirectory(target).resolve("inside.md"), "inside\n")

        held().use { directory ->
            shouldThrow<FileSystemException> { replacing(FileMode.OWNER_ONLY).replace(directory, "README.md", "new\n") }
        }

        namesIn(root) shouldContainExactly listOf("README.md")
    }

    /** Swapped for a link once it is held, the directory is still the one written in: never where the link leads. */
    @Test
    fun `a replace through a held directory swapped for a link lands in the directory held`() {
        assumeTrue(DirectoryHandles.givesHandles(root), "this platform gives no directory handle")
        val state = Files.createDirectory(root.resolve(".ps"))
        val aside = root.resolve("aside")

        DirectoryHandles.THROUGH_A_HANDLE.open(state).use { directory ->
            Files.move(state, aside)
            aLink(state, outside)
            replacing(FileMode.OWNER_ONLY).replace(directory, "timers.json", "{}")
        }

        namesIn(outside).shouldBeEmpty()
        namesIn(aside) shouldContainExactly listOf("timers.json")
    }

    private fun held(): DirectoryHandle = DirectoryHandles.THROUGH_A_HANDLE.open(root)

    private fun replacing(mode: FileMode) = FileReplacement(mode)

    // A file flag set or cleared with `chflags`, which macOS and the BSDs have; false where there is none to run.
    private fun flagged(directory: Path, flag: String): Boolean =
        runCatching { ProcessBuilder("chflags", flag, directory.toString()).start().waitFor() == 0 }.getOrDefault(false)

    private fun permissionsOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))
}
