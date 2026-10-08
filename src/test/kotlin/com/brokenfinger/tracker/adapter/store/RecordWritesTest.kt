package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aJunction
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.foldsTogether
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.attribute.PosixFilePermissions
import java.text.Normalizer

/**
 * The one bound every writer under the records repository goes through (#361), over a real directory.
 *
 * Each case that plants a link first shows the link reaching what it names, and then shows the write
 * leaving that alone: a file a link leads to keeps its bytes, and nothing is created where a link points.
 * A refusal is heard — one warning naming the path and why, never where a link leads — and thrown, unless
 * the writer asked to skip it.
 */
class RecordWritesTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    // A normal file, written exactly as before -------------------------------------------------------

    @Test
    fun `replaces a file whole and creates the directories on its way, saying nothing`() {
        val file = root.resolve("problems/1-x/README.md")

        val warnings = warningsWhile(RecordWrites::class) {
            problems().replace(file, "first, and the longer of the two\n")
            problems().replace(file, "second\n")
        }

        Files.readString(file) shouldBe "second\n"
        warnings.shouldBeEmpty()
    }

    /** Written beside the file and moved over it, and nothing of the move is left behind. */
    @Test
    fun `a replace leaves nothing beside the file`() {
        val file = root.resolve("problems/1-x/README.md")

        problems().replace(file, "page\n")
        problems().replace(file, "page again\n")

        namesIn(file.parent) shouldBe listOf("README.md")
    }

    /** A rewrite in place kept the file's mode, so a replace keeps it too. */
    @Test
    fun `a replaced file keeps the mode its owner gave it`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        val file = root.resolve("problems/1-x/README.md")
        problems().replace(file, "page\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"))

        problems().replace(file, "page again\n")

        permissionsOf(file) shouldBe "rw-rw-r--"
    }

    /** What a plain write gives a new file — the umask decides, as it did before. */
    @Test
    fun `a new file gets the mode a plain write gives one`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        val plain = Files.writeString(root.resolve("plain.md"), "plain\n")
        val file = root.resolve("problems/1-x/README.md")

        problems().replace(file, "page\n")

        permissionsOf(file) shouldBe permissionsOf(plain)
    }

    /** Code files have been written owner-only since the first write path (#18); a writer that asks keeps them so. */
    @Test
    fun `an owner-only writer leaves its file owner-only, even after someone widened it`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        val file = root.resolve("problems/1-x/Solution.java")
        val writes = RecordWrites.underProblems(RecordLayout(root), ownerOnly = true)
        writes.replace(file, "class Solution {}\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))

        writes.replace(file, "class Solution { }\n")

        permissionsOf(file) shouldBe "rw-------"
    }

    @Test
    fun `appends lines, creating the file, and first ends a line a crash cut short`() {
        val file = root.resolve("problems/1-x/runs.jsonl")
        problems().appendLine(file, "one")
        Files.writeString(file, "torn", APPEND)

        problems().appendLine(file, "two")

        Files.readString(file) shouldBe "one\ntorn\ntwo\n"
    }

    @Test
    fun `creates a new file and never replaces one`() {
        val file = root.resolve("problems/1-x/attempts/001.raw.jsonl")

        problems().createNew(file, "frames\n".toByteArray())

        Files.readString(file) shouldBe "frames\n"
        shouldThrow<FileAlreadyExistsException> { problems().createNew(file, "other\n".toByteArray()) }
        Files.readString(file) shouldBe "frames\n"
    }

    /** The statement's rule (#275): written when there is none, and left alone once there is. */
    @Test
    fun `writes a file once and leaves it after`() {
        val file = root.resolve("problems/1-x/statement.md")

        problems().writeOnce(file, "as first fetched\n")
        problems().writeOnce(file, "as fetched later\n")

        Files.readString(file) shouldBe "as first fetched\n"
    }

    @Test
    fun `deletes the names it is given, and creates nothing for a directory that is not there`() {
        val directory = Files.createDirectories(root.resolve("problems/1-x"))
        Files.writeString(directory.resolve("RunnerTest.java"), "stale")
        Files.writeString(directory.resolve("Solution.java"), "kept")

        problems().deleteIn(directory, listOf("RunnerTest.java", "runner_test.py"))
        problems().deleteIn(root.resolve("problems/2-y"), listOf("RunnerTest.java"))

        namesIn(directory) shouldBe listOf("Solution.java")
        Files.exists(root.resolve("problems/2-y")) shouldBe false
    }

    /** A root behind a link by configuration — macOS's `/var`, a `~/ps-records` link — is written as itself. */
    @Test
    fun `a records root reached through a link is written as itself`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val alias = aLink(outside.resolve("records"), root)

        RecordWrites.underProblems(RecordLayout(alias)).replace(alias.resolve("problems/1-x/README.md"), "page\n")

        Files.readString(root.resolve("problems/1-x/README.md")) shouldBe "page\n"
    }

    /** Every writer used to create the directories above its file, the records root included. */
    @Test
    fun `a records root that is not there yet is created`() {
        val fresh = root.resolve("fresh")

        RecordWrites.underRoot(fresh, setOf("log")).appendLine(fresh.resolve("log/submissions.jsonl"), "{}")

        Files.readString(fresh.resolve("log/submissions.jsonl")) shouldBe "{}\n"
    }

    // A link where the file should be ------------------------------------------------------------------

    /** Measured in #354's review: a problem's README linked to the push token, and the write overwrote the token. */
    @Test
    fun `a link where a replaced file should be is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val link = aLink(root.resolve("problems/1-x/README.md"), token)
        Files.readString(link) shouldContain A_PUSH_TOKEN_LINE

        val warning = warningsWhile(RecordWrites::class) { problems().replace(link, "page\n") }.single()

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.isSymbolicLink(link) shouldBe false
        Files.readString(link) shouldBe "page\n"
        warning shouldContain link.toString()
        warning shouldContain "a symbolic link"
        warning shouldNotContain A_PUSH_CREDENTIAL
        warning shouldNotContain "git-credentials"
    }

    /** Measured in #354's review: a dangling `statement.md` made the writer create a file outside `problems/`. */
    @Test
    fun `a dangling link where a file is written once is replaced, and nothing is created where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-write.md")
        val link = aLink(root.resolve("problems/1-x/statement.md"), nowhere)

        val warnings = warningsWhile(RecordWrites::class) { problems().writeOnce(link, "the problem\n") }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.isSymbolicLink(link) shouldBe false
        Files.readString(link) shouldBe "the problem\n"
        warnings.single() shouldContain link.toString()
    }

    /**
     * A linked statement reads as absent (#354), so it was fetched again at every boot and never written.
     * A link is not a file someone wrote, so the write-once rule does not keep it: it is replaced, once.
     */
    @Test
    fun `a link where a file is written once is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        val link = aLink(root.resolve("problems/1-x/statement.md"), elsewhere)

        problems().writeOnce(link, "the problem\n")

        Files.readString(elsewhere) shouldBe NOT_OURS
        Files.isSymbolicLink(link) shouldBe false
        Files.readString(link) shouldBe "the problem\n"
    }

    /** Measured in #354's review: a run's line was appended to the push token through a linked `runs.jsonl`. */
    @Test
    fun `an appended file that is a link is refused, and the file it leads to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val link = aLink(root.resolve("problems/1-x/runs.jsonl"), token)

        val warning = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { problems().appendLine(link, """{"code":"x"}""") }
        }.single()

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.isSymbolicLink(link) shouldBe true
        warning shouldContain link.toString()
        warning shouldContain "problems/1-x/runs.jsonl is a symbolic link"
        warning shouldNotContain A_PUSH_CREDENTIAL
        warning shouldNotContain "git-credentials"
    }

    @Test
    fun `an appended file that is a dangling link is refused, and nothing is created where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-an-append.jsonl")
        val link = aLink(root.resolve("log/submissions.jsonl"), nowhere)

        shouldThrow<RefusedWriteException> { RecordWrites.underRoot(root, setOf("log")).appendLine(link, "{}") }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
    }

    /**
     * Only a regular file is appended to. Opening a FIFO to write waits for a reader that never comes, so
     * what this pins is that the call returns at all; the timeout is what fails it if the check is removed.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `an appended file that is a FIFO is refused rather than waited on`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a FIFO")
        val fifo = Files.createDirectories(root.resolve("problems/1-x")).resolve("runs.jsonl")
        assumeTrue(madeFifo(fifo), "no mkfifo on this machine")

        val refusal = shouldThrow<RefusedWriteException> { problems().appendLine(fifo, "{}") }

        refusal.message shouldContain "is not a regular file"
    }

    /**
     * A hard link is the file itself under a second name, so an append wrote into whatever shared it: measured in
     * #361's review, a run's line landed in an outside file hard-linked as `runs.jsonl`. Refused where the `unix`
     * view can count a file's names; Windows has no such view, so the check does not run there.
     */
    @Test
    fun `an appended file with a second name is refused, and the file it shares keeps its bytes`() {
        assumeTrue(UNIX in root.fileSystem.supportedFileAttributeViews(), "no count of a file's names here")
        val elsewhere = aFileNotOurs(outside)
        val runs = Files.createDirectories(root.resolve("problems/1-x")).resolve("runs.jsonl")
        assumeTrue(runCatching { Files.createLink(runs, elsewhere) }.isSuccess, "no hard link between the two")

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { problems().appendLine(runs, """{"run":1}""") }
        }

        Files.readString(elsewhere) shouldBe NOT_OURS
        heard.single() shouldContain "problems/1-x/runs.jsonl is a hard link"
    }

    /** A new file is never created through a link either: not where the link stands, and not where it points. */
    @Test
    fun `a new file where a dangling link stands is not created, there or where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-copy.jsonl")
        val link = aLink(root.resolve("problems/1-x/attempts/001.raw.jsonl"), nowhere)

        shouldThrow<FileAlreadyExistsException> { problems().createNew(link, "frames\n".toByteArray()) }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.isSymbolicLink(link) shouldBe true
    }

    /** Deleting a link removes the link: a stale runner's name that is one goes, and what it named stays. */
    @Test
    fun `a stale file that is a link is deleted as a link, and the file it led to stays`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        val link = aLink(root.resolve("problems/1-x/RunnerTest.java"), elsewhere)

        problems().deleteIn(link.parent, listOf("RunnerTest.java"))

        Files.exists(link, NOFOLLOW_LINKS) shouldBe false
        Files.readString(elsewhere) shouldBe NOT_OURS
    }

    // A link on the way ----------------------------------------------------------------------------

    /** Every way of writing refuses it, the refusal is said once for the directory, and nothing lands where it led. */
    @Test
    fun `a problem directory that is a link is never written through`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/1-x"), outside)
        val writes = problems()

        val warnings = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { writes.replace(inProblem("README.md"), "page\n") }
            shouldThrow<RefusedWriteException> { writes.appendLine(inProblem("runs.jsonl"), "{}") }
            shouldThrow<RefusedWriteException> { writes.createNew(inProblem("attempts/001.raw.jsonl"), byteArrayOf()) }
            shouldThrow<RefusedWriteException> { writes.writeOnce(inProblem("statement.md"), "the problem\n") }
            writes.replaceOrSkip(inProblem("examples.json"), "[]") shouldBe false
        }

        namesIn(outside).shouldBeEmpty()
        warnings.single() shouldContain "problems/1-x is a symbolic link"
    }

    /** Resolving it would carry every writer to wherever it leads — here, the state directory. */
    @Test
    fun `a problems directory that is itself a link is never written through`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        aLink(root.resolve("problems"), root.resolve(".ps"))

        shouldThrow<RefusedWriteException> { problems().replace(root.resolve("problems/git-credentials"), "x") }
        shouldThrow<RefusedWriteException> { problems().replace(root.resolve("problems/README.md"), "index\n") }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        namesIn(root.resolve(".ps")) shouldBe listOf("git-credentials")
    }

    /** A read may follow a link that stays inside `problems/` (#354); a write follows none. */
    @Test
    fun `a directory link that stays inside problems is refused too`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val real = Files.createDirectories(root.resolve("problems/1-x"))
        aLink(root.resolve("problems/2-y"), real)

        shouldThrow<RefusedWriteException> { problems().replace(root.resolve("problems/2-y/README.md"), "page\n") }

        namesIn(real).shouldBeEmpty()
    }

    @Test
    fun `a directory link that leads nowhere creates nothing where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("never-made")
        aLink(root.resolve("problems/1-x/attempts"), nowhere)

        shouldThrow<RefusedWriteException> { problems().replace(inProblem("attempts/001.java"), "code\n") }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
    }

    /** A root-level writer — the submission log, the tag notes — walks from the root the same way. */
    @Test
    fun `a root-level directory that is a link is never written through`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("log"), outside)

        shouldThrow<RefusedWriteException> {
            RecordWrites.underRoot(root, setOf("log")).appendLine(root.resolve("log/submissions.jsonl"), "{}")
        }

        namesIn(outside).shouldBeEmpty()
    }

    @Test
    fun `nothing is deleted through a linked directory`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside, "RunnerTest.java")
        aLink(root.resolve("problems/1-x"), outside)

        val warnings = warningsWhile(RecordWrites::class) {
            problems().deleteIn(root.resolve("problems/1-x"), listOf("RunnerTest.java"))
        }

        Files.readString(elsewhere) shouldBe NOT_OURS
        warnings.single() shouldContain "problems/1-x is a symbolic link"
    }

    // The bound ----------------------------------------------------------------------------------

    /** The root holds what no problem writer may touch: the push token, the raw frames, the log. */
    @Test
    fun `a problem writer refuses a file beside the problems directory`() {
        val token = aPushTokenIn(root)

        shouldThrow<RefusedWriteException> { problems().replace(token, "overwritten\n") }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
    }

    @Test
    fun `a path that climbs out of problems is refused, though it starts inside`() {
        val token = aPushTokenIn(root)

        shouldThrow<RefusedWriteException> {
            problems().replace(root.resolve("problems/../.ps/git-credentials"), "overwritten\n")
        }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
    }

    /** A root-level writer is bounded too, and the reason it gives is the true one. */
    @Test
    fun `a root-level writer refuses a file outside the records repository, saying so`() {
        val refusal = shouldThrow<RefusedWriteException> {
            RecordWrites.underRoot(root, setOf("tags")).replace(outside.resolve("made-by-a-note.md"), "x")
        }

        refusal.message shouldContain "it lies outside the records repository"
        namesIn(outside).shouldBeEmpty()
    }

    /**
     * Measured as written in #361's review: lexical normalization took the `..` out before anything looked, so the
     * root's bound admitted the token's own file. No writer is handed such a path; the bound now says so itself.
     */
    @Test
    fun `a root-level writer refuses a path that climbs back out, and creates nothing`() {
        val token = aPushTokenIn(root)
        val log = RecordWrites.underRoot(root, setOf("log"))

        shouldThrow<RefusedWriteException> { log.replace(root.resolve("log/../.ps/git-credentials"), "overwritten\n") }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.exists(root.resolve("log")) shouldBe false
    }

    /** Measured as written in #361's review: the root's bound admitted git's own hooks. */
    @Test
    fun `a root-level writer refuses a name it does not keep, and creates nothing`() {
        val log = RecordWrites.underRoot(root, setOf("log"))

        shouldThrow<RefusedWriteException> { log.replace(root.resolve(".git/hooks/pre-commit"), "#!/bin/sh\n") }

        Files.exists(root.resolve(".git")) shouldBe false
    }

    /** A problem's files lie inside `problems/`; the name itself, as a file at the root, is no problem's. */
    @Test
    fun `a problem writer refuses the problems name itself, and creates nothing`() {
        val refusal = shouldThrow<RefusedWriteException> { problems().replace(root.resolve("problems"), "x") }

        refusal.message shouldContain "it lies outside problems/"
        Files.exists(root.resolve("problems")) shouldBe false
    }

    /** Under either bound, a `.` or `..` is refused even where it would land inside: a writer is never handed one. */
    @Test
    fun `a path naming dot or dot-dot is refused, even where it stays inside problems`() {
        shouldThrow<RefusedWriteException> { problems().replace(root.resolve("problems/./1-x/README.md"), "page\n") }
        shouldThrow<RefusedWriteException> { problems().replace(root.resolve("problems/1-x/../2-y/README.md"), "x") }

        Files.exists(root.resolve("problems")) shouldBe false
    }

    /** Only what lies below the root is judged: a root configured through `..` is written where it physically leads. */
    @Test
    fun `a records root configured through dot-dot is written where it leads`() {
        val configured = Files.createDirectories(root.resolve("elsewhere")).resolve("..")

        RecordWrites.underProblems(RecordLayout(configured)).replace(configured.resolve("problems/1-x/README.md"), "p")

        Files.readString(root.resolve("problems/1-x/README.md")) shouldBe "p"
    }

    /** Containment is by path element: `problems-old` begins with the same letters and is still outside. */
    @Test
    fun `a directory whose name only begins with problems is outside it`() {
        shouldThrow<RefusedWriteException> { problems().replace(root.resolve("problems-old/1-x/README.md"), "x") }

        Files.exists(root.resolve("problems-old")) shouldBe false
    }

    @Test
    fun `a directory where the file should be is refused, and stays`() {
        val directory = Files.createDirectories(root.resolve("problems/1-x/README.md"))

        val refusal = shouldThrow<RefusedWriteException> { problems().replace(directory, "page\n") }

        refusal.message shouldContain "a directory"
        Files.isDirectory(directory) shouldBe true
    }

    /**
     * Where the filesystem folds case, `problems` finds a hand-made `Problems`, whose real path the reader
     * already refuses (#354). A directory whose real path is not the one walked is refused before anything is
     * made inside it — which is also what stops a directory that is no link yet leads elsewhere.
     */
    @Test
    fun `a directory whose real path is not the one walked is refused`() {
        assumeTrue(foldsTogether(root, "Problems", "problems"), "this filesystem does not fold case")
        Files.createDirectories(root.resolve("Problems"))

        shouldThrow<RefusedWriteException> { problems().replace(inProblem("README.md"), "page\n") }

        namesIn(root.resolve("Problems")).shouldBeEmpty()
    }

    // Where the filesystem folds or rewrites names (#361's review) --------------------------------

    /**
     * In the tracker's image, a Linux container over a macOS bind mount, a real path echoes the name it was asked
     * for. So `problems` reached a hand-made alias and the write landed inside it: measured in #361's review. The
     * parent's listing names the alias as it is on disk, and that is what refuses it there.
     */
    @Test
    fun `in the image, where a real path echoes the name asked for, a folded alias is refused`() {
        Files.createDirectories(root.resolve("problems"))
        listOf("Problems", "PROBLEMS", "problemſ").forEach { alias ->
            val image = DiskAnswers(realPathOf = { it }, namesIn = listingWith("problems" to alias))

            val refusal = shouldThrow<RefusedWriteException> { problems(image).replace(inProblem("README.md"), "p") }

            refusal.message shouldContain "problems is not listed"
        }
        namesIn(root.resolve("problems")).shouldBeEmpty()
    }

    /**
     * HFS+ stores names in NFD, so the directory a first write makes for a Korean title is listed, and resolved, in
     * a form other than the NFC one walked. Measured in #361's review: the first write was refused, the second not.
     * Both sides are compared after NFC.
     */
    @Test
    fun `on HFS+, where names come back in NFD, the first write into a new Korean directory is accepted`() {
        val hfs = DiskAnswers(realPathOf = { Path.of(nfdOf(it.toRealPath().toString())) }, namesIn = ::nfdListing)
        val readme = root.resolve("problems/$KOREAN_DIRECTORY/README.md")

        problems(hfs).replace(readme, "page\n")

        Files.readString(readme) shouldBe "page\n"
    }

    /**
     * Where the real path answers the case on disk, as on the host and on Windows, a directory its parent lists is
     * still refused when its real path is another. Compared as text, since Windows' `Path.equals` ignores case. This
     * is what stops a directory that leads elsewhere without being a link, such as a Windows junction.
     */
    @Test
    fun `a directory its parent lists is refused when its real path is another`() {
        Files.createDirectories(root.resolve("problems"))
        val elsewhere = DiskAnswers(realPathOf = { aliasedProblems(it) })

        val refusal = shouldThrow<RefusedWriteException> { problems(elsewhere).replace(inProblem("README.md"), "p") }

        refusal.message shouldContain "problems resolves to another path"
        namesIn(root.resolve("problems")).shouldBeEmpty()
    }

    /**
     * The case above, made for real (#387): a junction is a Windows directory that leads elsewhere without being a
     * symbolic link, so the link check passes it. The real path does not: it answers where the junction leads, which
     * is not the path walked. Claimed since #361, and untested until a junction was made on windows-latest.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a junction where a problem directory should be is refused, and nothing is written where it leads`() {
        Files.createDirectories(root.resolve("problems"))
        val junction = aJunction(root.resolve("problems/1-x"), outside)
        try {
            val refusal = shouldThrow<RefusedWriteException> { problems().replace(inProblem("README.md"), "page\n") }

            refusal.message shouldContain "problems/1-x resolves to another path"
            namesIn(outside).shouldBeEmpty()
        } finally {
            Files.deleteIfExists(junction)
        }
    }

    // Said once, thrown unless skipped, never quoted ---------------------------------------------

    @Test
    fun `a refusal says the path and the reason once, and never where the link leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/1-x"), outside)
        val writes = problems()

        val warnings = warningsWhile(RecordWrites::class) {
            repeat(2) { writes.replaceOrSkip(inProblem("README.md"), "page\n") }
            writes.replaceOrSkip(inProblem("examples.json"), "[]")
        }

        val warning = warnings.single()
        warning shouldContain inProblem("README.md").toString()
        warning shouldContain "problems/1-x is a symbolic link"
        warning shouldNotContain outside.toString()
    }

    /** Thrown to a writer whose failures reach its caller; a writer that carries on asks to skip instead. */
    @Test
    fun `a refusal is thrown with its reason, unless the writer asked to skip it`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/1-x"), outside)

        val refusal = shouldThrow<RefusedWriteException> { problems().replace(inProblem("README.md"), "page\n") }

        refusal.message shouldContain inProblem("README.md").toString()
        refusal.message shouldContain "problems/1-x is a symbolic link"
        refusal.message shouldNotContain outside.toString()
        problems().replaceOrSkip(inProblem("README.md"), "page\n") shouldBe false
    }

    /**
     * A refusal is the bound's own answer. Any other failure is not, and reaches the writer as it always did: a
     * writer that skips refusals still fails on a directory that will not take the file.
     */
    @Test
    fun `a failure that is no refusal is thrown, even to a writer that skips refusals`() {
        assumeTrue(keepsPosixPermissions(root), "this test changes POSIX permissions")
        val directory = Files.createDirectories(root.resolve("problems/1-x"))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
        try {
            assumeTrue(!Files.isWritable(directory), "a superuser writes anyway")
            shouldThrow<AccessDeniedException> { problems().replaceOrSkip(directory.resolve("README.md"), "page\n") }
        } finally {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun problems() = RecordWrites.underProblems(RecordLayout(root))

    private fun problems(disk: DiskAnswers) = RecordWrites.underProblems(RecordLayout(root), disk = disk)

    /** The real listing, except that [renamed]'s first name is listed as its second, as a folding disk lists it. */
    private fun listingWith(renamed: Pair<String, String>): (Path) -> Set<String> = { directory ->
        namesOnDisk(directory).map { if (it == renamed.first) renamed.second else it }.toSet()
    }

    private fun nfdListing(directory: Path): Set<String> = namesOnDisk(directory).map(::nfdOf).toSet()

    private fun nfdOf(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

    // `problems` answered as `Problems`, a real path other than the one walked; everything else as it is.
    private fun aliasedProblems(path: Path): Path {
        if (path.fileName?.toString() == "problems") return path.resolveSibling("Problems")
        return path.toRealPath()
    }

    private fun inProblem(relative: String): Path = root.resolve("problems/1-x").resolve(relative)

    private fun permissionsOf(file: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))

    private companion object {
        const val UNIX = "unix"

        /** A problem directory named from a Korean title, in NFC as the layout makes it. */
        const val KOREAN_DIRECTORY = "120804-두-수의-곱-구하기"
    }
}
