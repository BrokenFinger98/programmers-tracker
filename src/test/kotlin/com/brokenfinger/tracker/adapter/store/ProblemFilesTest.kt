package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one bound every reader of a file under `problems/` goes through (#354), over a real directory.
 *
 * Each refusal first reads the file through the very path the reader is handed, so every case here is
 * shown to be a real exposure before it is shown to be refused. A refusal is heard — one warning naming
 * the path and why — while a file that is simply not there, the normal path, says nothing.
 */
class ProblemFilesTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `reads a regular file that lies under the problems directory, and says nothing`() {
        val file = written("problems/1-x/statement.md", "the problem\n")

        val warnings = warningsWhile(ProblemFiles::class) {
            files().readString(file) shouldBe "the problem\n"
            files().readAllBytes(file)?.decodeToString() shouldBe "the problem\n"
        }

        warnings.shouldBeEmpty()
    }

    /** The root holds what no reader may lead to; a path straight at it is refused like a link to it. */
    @Test
    fun `a file beside the problems directory is not read, though it exists`() {
        val token = aPushTokenIn(root)

        Files.readString(token) shouldContain A_PUSH_TOKEN_LINE
        files().readString(token).shouldBeNull()
        files().readAllBytes(token).shouldBeNull()
    }

    /** Containment is by path element: `problems-old` begins with the same letters and is still outside. */
    @Test
    fun `a directory whose name only begins with problems is outside it`() {
        val file = written("problems-old/1-x/statement.md", "not ours")

        files().readString(file).shouldBeNull()
    }

    /** The normal path — a statement not fetched yet, a run log before the first run — so nothing is said. */
    @Test
    fun `a file that is not there is absent, not an error, and not a warning`() {
        val warnings = warningsWhile(ProblemFiles::class) {
            files().readString(root.resolve("problems/1-x/statement.md")).shouldBeNull()
        }

        warnings.shouldBeEmpty()
    }

    @Test
    fun `a directory where a file should be is absent, with a warning that says so`() {
        val directory = Files.createDirectories(root.resolve("problems/1-x/statement.md"))

        val warnings = warningsWhile(ProblemFiles::class) {
            files().readString(directory).shouldBeNull()
            files().readAllBytes(directory).shouldBeNull()
        }

        warnings shouldHaveSize 2
        warnings.forEach {
            it shouldContain directory.toString()
            it shouldContain "not a regular file"
        }
    }

    /**
     * Strict, as [Files.readString] is: a statement saved in another encoding is absent rather than
     * served as replacement characters. The bytes are still there for a reader that decodes leniently.
     */
    @Test
    fun `bytes that are not UTF-8 are still bytes, and are no string`() {
        val file = root.resolve("problems/1-x/statement.md")
        Files.createDirectories(file.parent)
        Files.write(file, CP949_SYLLABLE)

        files().readAllBytes(file)?.toList() shouldBe CP949_SYLLABLE.toList()
        val warnings = warningsWhile(ProblemFiles::class) { files().readString(file).shouldBeNull() }

        warnings.single() shouldContain "MalformedInputException"
    }

    /**
     * Any other failure is named by its kind alone. An exception's message carries a path, and the one it
     * carries can be the path a link resolved to — here the file the link names — so it is never quoted.
     */
    @Test
    fun `a file that cannot be opened is absent, with a warning that names the failure and not its message`() {
        assumeTrue(canPlantLinksIn(root), "this test changes permissions and makes a symbolic link")
        val file = written("problems/120804-the-target/attempts/001.java", "select 1\n")
        val link = aLink(root.resolve("problems/2-y/attempts/001.java"), file)
        Files.setPosixFilePermissions(file, emptySet())
        assumeTrue(!Files.isReadable(file), "a superuser reads it anyway")

        val warning = warningsWhile(ProblemFiles::class) { files().readString(link).shouldBeNull() }.single()

        warning shouldContain link.toString()
        warning shouldContain "AccessDeniedException"
        warning shouldNotContain "120804-the-target"
    }

    // Links. Git stores them, so one under problems/ can arrive with a clone or a pull, not only by hand —
    // and the path a reader is handed still reads `problems/...`.

    /** What matters is where a link leads, not that it is one. */
    @Test
    fun `a link that stays inside the problems directory is read as the file it names`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val file = written("problems/1-x/attempts/001.java", "select 1\n")
        val link = aLink(root.resolve("problems/2-y/attempts/001.java"), file)

        files().readString(link) shouldBe "select 1\n"
    }

    /** Where a link finally leads is what counts, not where it passes on the way. */
    @Test
    fun `a link that leaves the problems directory and comes back in reads as the file it finally names`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val file = written("problems/1-x/statement.md", "the problem\n")
        val hop = aLink(outside.resolve("hop.md"), file)
        val link = aLink(root.resolve("problems/2-y/statement.md"), hop)

        files().readString(link) shouldBe "the problem\n"
    }

    @Test
    fun `a link to the push token beside the problems directory is not followed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val link = aLink(root.resolve("problems/1-x/statement.md"), aPushTokenIn(root))

        Files.readString(link) shouldContain A_PUSH_TOKEN_LINE
        files().readString(link).shouldBeNull()
        files().readAllBytes(link).shouldBeNull()
    }

    /**
     * Heard, and still nothing quoted: one warning naming the path the reader was handed and why — never
     * what lies behind it, and never where the link leads, which here is the token's own file.
     */
    @Test
    fun `a link out is refused with one warning that names the path it was reached by and quotes nothing`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val link = aLink(root.resolve("problems/1-x/statement.md"), aPushTokenIn(root))

        val warning = warningsWhile(ProblemFiles::class) { files().readString(link).shouldBeNull() }.single()

        warning shouldContain link.toString()
        warning shouldContain "leads out of problems/"
        warning shouldNotContain A_PUSH_CREDENTIAL
        warning shouldNotContain "git-credentials"
    }

    @Test
    fun `a problem directory linked out of the problems directory is not followed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        Files.writeString(outside.resolve("statement.md"), "not ours")
        aLink(root.resolve("problems/1-x"), outside)
        val candidate = root.resolve("problems/1-x/statement.md")

        Files.readString(candidate) shouldBe "not ours"
        files().readString(candidate).shouldBeNull()
    }

    /** Resolving `problems` as well would carry the bound to wherever the link leads — here, onto the token. */
    @Test
    fun `a problems directory that is itself a link to the state directory is not followed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aPushTokenIn(root)
        aLink(root.resolve("problems"), root.resolve(".ps"))
        val candidate = root.resolve("problems/git-credentials")

        Files.readString(candidate) shouldContain A_PUSH_TOKEN_LINE
        files().readString(candidate).shouldBeNull()
    }

    @Test
    fun `a problems directory that is itself a link out of the repository is not followed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        Files.writeString(Files.createDirectories(outside.resolve("1-x")).resolve("statement.md"), "not ours")
        aLink(root.resolve("problems"), outside)
        val candidate = root.resolve("problems/1-x/statement.md")

        Files.readString(candidate) shouldBe "not ours"
        files().readString(candidate).shouldBeNull()
    }

    /** What is resolved is the repository root, so a records directory reached through a link still reads. */
    @Test
    fun `a records root reached through a link still reads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        written("problems/1-x/statement.md", "the problem\n")
        val alias = aLink(outside.resolve("records"), root)
        val files = ProblemFiles(RecordLayout(alias))

        files.readString(alias.resolve("problems/1-x/statement.md")) shouldBe "the problem\n"
    }

    /** A dangling link reads as a missing file, which is what the filesystem says it is: no warning. */
    @Test
    fun `a link that leads nowhere is absent, not an error, and not a warning`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val link = aLink(root.resolve("problems/1-x/statement.md"), root.resolve("problems/1-x/gone.md"))

        val warnings = warningsWhile(ProblemFiles::class) {
            files().readString(link).shouldBeNull()
            files().readAllBytes(link).shouldBeNull()
        }

        warnings.shouldBeEmpty()
    }

    /**
     * Only a regular file is read. A FIFO would block the calling thread for a writer that never comes,
     * so what this pins is that the call returns at all. It runs in a thread of its own because the
     * `open` that blocks cannot be interrupted, and the timeout is what fails it if the check is removed.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO is absent, and is not waited on`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a FIFO")
        val fifo = root.resolve("problems/1-x/runs.jsonl")
        Files.createDirectories(fifo.parent)
        assumeTrue(madeFifo(fifo), "no mkfifo on this machine")

        files().readString(fifo).shouldBeNull()
        files().readAllBytes(fifo).shouldBeNull()
    }

    private fun written(relative: String, text: String): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    private fun files() = ProblemFiles(RecordLayout(root))

    private companion object {
        /** A Korean syllable in CP949, which a legacy editor writes: its lead byte is no UTF-8 lead byte. */
        val CP949_SYLLABLE = byteArrayOf(0xB0.toByte(), 0xA1.toByte())
    }
}
