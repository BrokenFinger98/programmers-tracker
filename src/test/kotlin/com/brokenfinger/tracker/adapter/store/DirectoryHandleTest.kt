package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.fixtures.sealedWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The directory a writer of state holds between the check and the write (#374), held both ways there are: by its
 * path, as on Windows, and through a handle, as on Linux and macOS. One contract — every case of it runs against each —
 * and one difference, pinned at the end: where a write goes when a link is swapped in for the directory after it was
 * opened.
 */
class DirectoryHandleTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    /** How a directory is held. Where the platform gives no handle, the second is held by path too. */
    enum class Held(val handles: DirectoryHandles) {
        BY_PATH(DirectoryHandles.BY_PATH),
        THROUGH_A_HANDLE(DirectoryHandles.THROUGH_A_HANDLE),
    }

    // The directories below one, made and opened -------------------------------------------------

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a directory absent below is made, and written in through what it opens`(held: Held) {
        opened(held).use { directory -> directory.child("raw")!!.use { it.append("s.jsonl", bytes("frame\n")) } }

        Files.readString(root.resolve("raw/s.jsonl")) shouldBe "frame\n"
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a link where a directory should be opens nothing, and nothing is made where it leads`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("raw"), outside)

        opened(held).use { it.child("raw").shouldBeNull() }

        namesIn(outside).shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a file where a directory should be opens nothing`(held: Held) {
        Files.writeString(root.resolve("raw"), "a file\n")

        opened(held).use { it.child("raw").shouldBeNull() }
    }

    // The writes, none of them through a link ------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a file is made new and empty, in the mode asked for`(held: Held) {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")

        opened(held).use { it.create("new.tmp", OWNER_ONLY) }

        Files.size(root.resolve("new.tmp")) shouldBe 0L
        permissionsOf(root.resolve("new.tmp")) shouldBe "rw-------"
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a file is never made where anything stands, a link included`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        aLink(root.resolve("new.tmp"), elsewhere)

        opened(held).use { shouldThrow<FileAlreadyExistsException> { it.create("new.tmp", OWNER_ONLY) } }

        Files.readString(elsewhere) shouldBe NOT_OURS
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a write replaces what a file held`(held: Held) {
        Files.writeString(root.resolve("doc.json"), "the older and longer document\n")

        opened(held).use { it.write("doc.json", bytes("new\n")) }

        Files.readString(root.resolve("doc.json")) shouldBe "new\n"
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a write makes no file and follows no link`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        aLink(root.resolve("doc.json"), elsewhere)

        opened(held).use { directory ->
            shouldThrow<IOException> { directory.write("doc.json", bytes("new\n")) }
            shouldThrow<NoSuchFileException> { directory.write("absent.json", bytes("new\n")) }
        }

        Files.readString(elsewhere) shouldBe NOT_OURS
        Files.exists(root.resolve("absent.json")) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `an append adds to the end, and makes the file when absent`(held: Held) {
        opened(held).use { directory ->
            directory.append("s.jsonl", bytes("one\n"))
            directory.append("s.jsonl", bytes("two\n"))
        }

        Files.readString(root.resolve("s.jsonl")) shouldBe "one\ntwo\n"
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `an append follows no link`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        aLink(root.resolve("s.jsonl"), elsewhere)

        opened(held).use { shouldThrow<IOException> { it.append("s.jsonl", bytes("frame\n")) } }

        Files.readString(elsewhere) shouldBe NOT_OURS
    }

    // Moves and deletes -----------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a move replaces a link at its target rather than writing through it`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        Files.writeString(root.resolve("temp"), "new\n")
        aLink(root.resolve("doc.json"), elsewhere)

        opened(held).use { it.move("temp", it, "doc.json") } shouldBe true

        Files.isSymbolicLink(root.resolve("doc.json")) shouldBe false
        Files.readString(root.resolve("doc.json")) shouldBe "new\n"
        Files.readString(elsewhere) shouldBe NOT_OURS
        namesIn(root) shouldContainExactly listOf("doc.json")
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a move goes into another directory held`(held: Held) {
        Files.writeString(root.resolve("s.jsonl"), "frame\n")

        opened(held).use { raw -> raw.child("recorded")!!.use { raw.move("s.jsonl", it, "s.jsonl") } }

        Files.readString(root.resolve("recorded/s.jsonl")) shouldBe "frame\n"
        Files.exists(root.resolve("s.jsonl")) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a move of nothing is false, and makes nothing`(held: Held) {
        opened(held).use { it.move("absent", it, "doc.json") } shouldBe false

        namesIn(root).shouldBeEmpty()
    }

    /** As a replace by path always did: an empty directory gives way, and one holding something fails the move. */
    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a move replaces an empty directory at its target, and fails over one holding something`(held: Held) {
        Files.writeString(root.resolve("temp"), "new\n")
        Files.createDirectory(root.resolve("empty"))
        Files.writeString(Files.createDirectory(root.resolve("full")).resolve("inside"), "inside\n")

        opened(held).use { directory ->
            directory.move("temp", directory, "empty") shouldBe true
            Files.writeString(root.resolve("temp"), "again\n")
            shouldThrow<FileSystemException> { directory.move("temp", directory, "full") }
        }

        Files.readString(root.resolve("empty")) shouldBe "new\n"
        Files.readString(root.resolve("temp")) shouldBe "again\n"
        Files.readString(root.resolve("full/inside")) shouldBe "inside\n"
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a delete takes a link away as a link, and is false for nothing`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        aLink(root.resolve("s.jsonl"), elsewhere)

        opened(held).use { directory ->
            directory.delete("s.jsonl") shouldBe true
            directory.delete("s.jsonl") shouldBe false
        }

        Files.exists(root.resolve("s.jsonl"), NOFOLLOW_LINKS) shouldBe false
        Files.readString(elsewhere) shouldBe NOT_OURS
    }

    // Modes, identity and names ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a regular file's mode is read and set, and a link has none to read`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val document = Files.writeString(root.resolve("doc.json"), "{}")
        Files.setPosixFilePermissions(document, PosixFilePermissions.fromString("rw-rw-r--"))
        aLink(root.resolve("link.json"), document)

        opened(held).use { directory ->
            directory.permissionsOf("doc.json") shouldBe PosixFilePermissions.fromString("rw-rw-r--")
            directory.permissionsOf("link.json").shouldBeNull()
            directory.setPermissions("doc.json", OWNER_ONLY)
        }

        permissionsOf(document) shouldBe "rw-------"
    }

    /** What the raw log asks before an orphan's append (#378): a link, a directory or a FIFO is no file to append to. */
    @ParameterizedTest
    @EnumSource(Held::class)
    fun `what stands at a name is told apart from a regular file, without following a link`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        Files.writeString(root.resolve("file.jsonl"), "frame\n")
        aLink(root.resolve("link.jsonl"), aFileNotOurs(outside))
        Files.createDirectory(root.resolve("directory.jsonl"))
        val fifo = madeFifo(root.resolve("fifo.jsonl"))

        opened(held).use { directory ->
            directory.isThereButNotAFile("file.jsonl") shouldBe false
            directory.isThereButNotAFile("absent.jsonl") shouldBe false
            directory.isThereButNotAFile("link.jsonl") shouldBe true
            directory.isThereButNotAFile("directory.jsonl") shouldBe true
            directory.isThereButNotAFile("fifo.jsonl") shouldBe fifo
        }
    }

    /** As `Files.exists` answers, so a release of held frames that asks never throws for it (#378). */
    @ParameterizedTest
    @EnumSource(Held::class)
    fun `what cannot be looked at is taken for nothing there`(held: Held) {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val sealed = Files.createDirectory(root.resolve("sealed"))
        aLink(sealed.resolve("link.jsonl"), outside)

        held.handles.open(sealed).use { directory ->
            sealedWhile(sealed) {
                assumeTrue(!Files.isReadable(sealed), "a superuser reads it anyway")
                directory.isThereButNotAFile("link.jsonl") shouldBe false
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Held::class)
    fun `it is at the directory it was opened on, and at no other`(held: Held) {
        opened(held).use { directory ->
            directory.isAt(root) shouldBe true
            directory.isAt(outside) shouldBe false
        }
    }

    /**
     * An absolute name would leave the directory behind, one of a single segment included — such as `/var`, which this
     * names, a directory nothing can be appended to — and a second segment would be reached through a link.
     */
    @ParameterizedTest
    @EnumSource(Held::class)
    fun `a name is one name, never a path`(held: Held) {
        val rootedAndSingle = outside.root.resolve(outside.getName(0)).toString()
        val names = listOf("raw/s.jsonl", outside.resolve("s.jsonl").toString(), rootedAndSingle, "..", ".", "")

        opened(held).use { directory ->
            names.forEach { name -> shouldThrow<IllegalArgumentException> { directory.append(name, bytes("frame\n")) } }
        }

        namesIn(outside).shouldBeEmpty()
    }

    // The one difference: a link swapped in for the directory once it is open (N10) -------------------

    /**
     * By path, as on Windows: a write goes where the path leads when it is made, the cost #374 accepts there. Through a
     * handle it stays in the directory: `SecureDirectoryHandleTest`.
     */
    @Test
    fun `held by its path, a directory swapped for a link is written through the link`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val state = Files.createDirectory(root.resolve(".ps"))

        DirectoryHandles.BY_PATH.open(state).use { directory ->
            swappedForALink(state, root.resolve("aside"))
            directory.append("s.jsonl", bytes("frame\n"))
        }

        namesIn(outside) shouldContainExactly listOf("s.jsonl")
    }

    // Which one a platform gives --------------------------------------------------------------------

    /** Linux and macOS give `SecureDirectoryStream`; Windows does not. CI runs all three, so a change shows here. */
    @Test
    fun `every platform the tracker runs on gives a handle, except Windows`() {
        DirectoryHandles.givesHandles(root) shouldBe !isWindows()
    }

    @Test
    fun `this platform's directories are held through a handle wherever it gives one`() {
        DirectoryHandles.ON_THIS_PLATFORM.open(root).use { (it is SecureDirectoryHandle) shouldBe !isWindows() }
    }

    @Test
    fun `where no handle is given, every directory is held by its path, and that is said once`() {
        var selected = DirectoryHandles.THROUGH_A_HANDLE

        val heard = warningsWhile(DirectoryHandles::class) {
            selected = DirectoryHandles.selected(givesHandles = false)
        }

        heard.single() shouldContain "written by path"
        selected.open(root).use { (it is PathDirectoryHandle) shouldBe true }
    }

    @Test
    fun `where a handle is given, nothing is said`() {
        warningsWhile(DirectoryHandles::class) { DirectoryHandles.selected(givesHandles = true) }.shouldBeEmpty()
    }

    private fun opened(held: Held): DirectoryHandle = held.handles.open(root)

    // The directory moved away, and a link put in its place.
    private fun swappedForALink(state: Path, aside: Path) {
        Files.move(state, aside)
        aLink(state, outside)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun permissionsOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))

    private companion object {
        val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}
