package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aJunction
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * How a file a writer keeps at the records repository's own level is read (#387): through the walk its writer takes,
 * so what a writer refuses a reader refuses too, and nothing is read from where a link leads.
 *
 * Each case that plants a link first reads the file through the very path the reader is handed, so it is shown to be a
 * real exposure before it is shown to be refused. Nothing there is null and silent; anything else standing there is
 * refused, said once, and thrown — never answered as absent, which a reader of the history would take for nothing
 * recorded.
 */
class RecordReadsTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `reads a regular file at the root's own level, and says nothing`() {
        val log = written("log/submissions.jsonl", "{}\n")

        val warnings = warningsWhile(RecordReads::class) {
            logReads().readAllBytes(log)?.decodeToString() shouldBe "{}\n"
        }

        warnings.shouldBeEmpty()
    }

    /** The normal path: a repository that has recorded nothing yet. Nothing is made by looking. */
    @Test
    fun `nothing there is null, said nothing, and made nothing`() {
        val warnings = warningsWhile(RecordReads::class) {
            logReads().readAllBytes(root.resolve("log/submissions.jsonl")).shouldBeNull()
            RecordReads.underRoot(root.resolve("not-yet"), setOf("log"))
                .readAllBytes(root.resolve("not-yet/log/submissions.jsonl")).shouldBeNull()
        }

        warnings.shouldBeEmpty()
        namesIn(root).shouldBeEmpty()
    }

    // A link where the file should be ---------------------------------------------------------------

    /** Heard once, naming the path the reader was handed and why: never what lies behind the link, nor where it leads. */
    @Test
    fun `a link where the file should be is refused, and never says where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val log = aLink(root.resolve("log/submissions.jsonl"), aPushTokenIn(root))
        Files.readString(log) shouldContain A_PUSH_TOKEN_LINE

        val warning = warningsWhile(RecordReads::class) {
            shouldThrow<RefusedReadException> { logReads().readAllBytes(log) }
        }.single()

        warning shouldContain log.toString()
        warning shouldContain "log/submissions.jsonl is a symbolic link"
        warning shouldNotContain A_PUSH_CREDENTIAL
        warning shouldNotContain "git-credentials"
    }

    /** A dangling link is a link, which its writer refuses: read as absent, a log would read as nothing recorded. */
    @Test
    fun `a dangling link where the file should be is refused, not absent`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val log = aLink(root.resolve("log/submissions.jsonl"), outside.resolve("gone.jsonl"))

        val refusal = shouldThrow<RefusedReadException> { logReads().readAllBytes(log) }

        refusal.message shouldContain "log/submissions.jsonl is a symbolic link"
    }

    @Test
    fun `a directory on the way that is a link is refused, and what lies behind it is not read`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aFileNotOurs(outside, "submissions.jsonl")
        aLink(root.resolve("log"), outside)
        val log = root.resolve("log/submissions.jsonl")
        Files.readString(log) shouldBe NOT_OURS

        val refusal = shouldThrow<RefusedReadException> { logReads().readAllBytes(log) }

        refusal.message shouldContain "log is a symbolic link"
        refusal.message shouldNotContain outside.toString()
    }

    @Test
    fun `a directory where the file should be is refused`() {
        val directory = Files.createDirectories(root.resolve("log/submissions.jsonl"))

        val refusal = shouldThrow<RefusedReadException> { logReads().readAllBytes(directory) }

        refusal.message shouldContain "log/submissions.jsonl is not a regular file"
    }

    /**
     * Only a regular file is read. A FIFO would block the calling thread for a writer that never comes, so what this
     * pins is that the call returns at all; the timeout fails it if the check is removed.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO where the file should be is refused rather than waited on`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a FIFO")
        val fifo = Files.createDirectories(root.resolve("log")).resolve("submissions.jsonl")
        assumeTrue(madeFifo(fifo), "no mkfifo on this machine")

        shouldThrow<RefusedReadException> { logReads().readAllBytes(fifo) }
    }

    // The bound ------------------------------------------------------------------------------------

    /** The root keeps what no reader of the log may read: the push token, beside it. */
    @Test
    fun `a name the root does not keep for this reader is refused, though the file exists`() {
        val token = aPushTokenIn(root)

        val refusal = shouldThrow<RefusedReadException> { logReads().readAllBytes(token) }

        refusal.message shouldContain "none of the names"
    }

    @Test
    fun `a path that climbs back out is refused, though it starts inside`() {
        aPushTokenIn(root)

        shouldThrow<RefusedReadException> { logReads().readAllBytes(root.resolve("log/../.ps/git-credentials")) }
    }

    /**
     * In the tracker's image, a Linux container over a macOS bind mount, a real path echoes the name it was asked for,
     * so only the parent's listing says that `log` is a hand-made alias. Its writer refuses it there, so it is read
     * nowhere either.
     */
    @Test
    fun `in the image, a folded alias where log should be is refused`() {
        written("log/submissions.jsonl", "{}\n")
        val image = DiskAnswers(realPathOf = { it }, namesIn = { listingWith(it, "log" to "Log") })

        val refusal = shouldThrow<RefusedReadException> {
            RecordReads.underRoot(root, setOf("log"), image).readAllBytes(root.resolve("log/submissions.jsonl"))
        }

        refusal.message shouldContain "log is not listed"
    }

    /** What a junction answers on Windows: listed by its parent, and resolving somewhere else. */
    @Test
    fun `a directory whose real path is another is refused`() {
        written("log/submissions.jsonl", "{}\n")
        val elsewhere = DiskAnswers(realPathOf = { if (it.endsWith("log")) outside else it.toRealPath() })

        val refusal = shouldThrow<RefusedReadException> {
            RecordReads.underRoot(root, setOf("log"), elsewhere).readAllBytes(root.resolve("log/submissions.jsonl"))
        }

        refusal.message shouldContain "log resolves to another path"
    }

    /** The case above, made for real on windows-latest: a junction is read through until something asks where it leads. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a junction where log should be is refused, and what lies behind it is not read`() {
        aFileNotOurs(outside, "submissions.jsonl")
        val junction = aJunction(root.resolve("log"), outside)
        try {
            Files.readString(junction.resolve("submissions.jsonl")) shouldBe NOT_OURS

            val refusal = shouldThrow<RefusedReadException> {
                logReads().readAllBytes(junction.resolve("submissions.jsonl"))
            }

            refusal.message shouldContain "log resolves to another path"
        } finally {
            Files.deleteIfExists(junction)
        }
    }

    /**
     * A hard link is the file itself rather than a pointer to one, so it is read, as #354 accepted for reads. Its
     * writer will not append to it (#361); git cannot deliver one, and making it takes a shell here.
     */
    @Test
    fun `a hard link is read, being the file itself`() {
        val elsewhere = aFileNotOurs(outside)
        val log = Files.createDirectories(root.resolve("log")).resolve("submissions.jsonl")
        assumeTrue(runCatching { Files.createLink(log, elsewhere) }.isSuccess, "no hard link between the two")

        logReads().readAllBytes(log)?.decodeToString() shouldBe NOT_OURS
    }

    /**
     * A directory above the file that cannot be searched says nothing about whether the file is there. Answered as
     * absent, a log would read as nothing recorded; it is an error the caller sees instead.
     */
    @Test
    fun `a directory that cannot be searched is an error, not an absent file`() {
        assumeTrue(keepsPosixPermissions(root), "this test changes POSIX permissions")
        val log = written("log/submissions.jsonl", "{}\n")
        Files.setPosixFilePermissions(log.parent, PosixFilePermissions.fromString("rw-------"))
        try {
            assumeTrue(!Files.isReadable(log), "a superuser searches anyway")
            shouldThrow<AccessDeniedException> { logReads().readAllBytes(log) }
        } finally {
            Files.setPosixFilePermissions(log.parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    /** The same one directory up: the walk cannot say whether `log/` is there, which is not saying it is not. */
    @Test
    fun `a root that cannot be searched is an error, not an absent directory`() {
        assumeTrue(keepsPosixPermissions(root), "this test changes POSIX permissions")
        val log = written("log/submissions.jsonl", "{}\n")
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rw-------"))
        try {
            assumeTrue(!Files.isReadable(log), "a superuser searches anyway")
            shouldThrow<AccessDeniedException> { logReads().readAllBytes(log) }
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // Said once, thrown every time ----------------------------------------------------------------

    @Test
    fun `a refusal is said once for its reason, and thrown every time`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("log"), outside)
        val reads = logReads()
        val log = root.resolve("log/submissions.jsonl")

        val warnings = warningsWhile(RecordReads::class) {
            repeat(2) { shouldThrow<RefusedReadException> { reads.readAllBytes(log) } }
        }

        warnings.single() shouldContain "log is a symbolic link"
    }

    /** The words of a refusal, pinned before #386 shares how a reason is said once. */
    @Test
    fun `a refusal is said in exactly these words`() {
        val token = aPushTokenIn(root)

        val heard = warningsWhile(RecordReads::class) {
            shouldThrow<RefusedReadException> { logReads().readAllBytes(token) }
        }

        heard.single() shouldBe "Not reading $token: it is none of the names kept at the root for it: log. A file " +
            "the records repository keeps is read through real directories and never through a link, as it is " +
            "written (#387). Said once for this reason."
    }

    private fun logReads() = RecordReads.underRoot(root, setOf("log"))

    private fun written(relative: String, text: String): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    /** The real listing, except that [renamed]'s first name is listed as its second, as a folding disk lists it. */
    private fun listingWith(directory: Path, renamed: Pair<String, String>): Set<String> =
        namesOnDisk(directory).map { if (it == renamed.first) renamed.second else it }.toSet()
}
