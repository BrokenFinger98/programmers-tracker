package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.namesIn
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * What the walk answers, before either side says it (#361, #387): the place a file may be, or the reason it may not.
 * [RecordWritesTest] and [RecordReadsTest] show what a writer and a reader make of each answer; this is the answer.
 */
class RecordBoundTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `a writer's walk makes the directories on the way, and answers the file in the last`() {
        val file = problems().fileIn(root.resolve("problems/1-x/attempts/001.java"), creating = true)

        file shouldBe realRoot().resolve("problems/1-x/attempts/001.java")
        Files.isDirectory(root.resolve("problems/1-x/attempts")) shouldBe true
    }

    /** A reader walks the same way and makes nothing: a directory that is not there means nothing is there. */
    @Test
    fun `a walk that makes nothing answers null for a directory that is not there, and makes none`() {
        problems().fileIn(root.resolve("problems/1-x/runs.jsonl"), creating = false).shouldBeNull()

        namesIn(root).shouldBeEmpty()
    }

    /** A directory itself, as the state directory is asked for: walked, made where absent, and answered as itself. */
    @Test
    fun `a directory is made where absent and answered as itself`() {
        val made = RecordBound.underRoot(root, setOf(".ps")).made(root.resolve(".ps"))

        made shouldBe realRoot().resolve(".ps")
        Files.isDirectory(root.resolve(".ps"), LinkOption.NOFOLLOW_LINKS) shouldBe true
    }

    @Test
    fun `a directory made through a link is out of bounds, and nothing is made where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".ps"), outside)

        val out = shouldThrow<OutOfBounds> { RecordBound.underRoot(root, setOf(".ps")).made(root.resolve(".ps/raw")) }

        out.reason shouldBe ".ps is a symbolic link"
        namesIn(outside).shouldBeEmpty()
    }

    @Test
    fun `a directory that exists is answered as itself, and one that does not as null`() {
        Files.createDirectories(root.resolve("problems/1-x"))

        problems().existing(root.resolve("problems/1-x")) shouldBe realRoot().resolve("problems/1-x")
        problems().existing(root.resolve("problems/2-y")).shouldBeNull()
    }

    /** The reason names the part of the path at fault, relative to the root, and never where the link leads. */
    @Test
    fun `a link on the way is out of bounds, for a reason that names it and not where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("log"), outside)

        val out = shouldThrow<OutOfBounds> { atRoot().fileIn(root.resolve("log/submissions.jsonl"), creating = false) }

        out.reason shouldBe "log is a symbolic link"
        out.reason shouldNotContain outside.toString()
    }

    @Test
    fun `a name the root does not keep here is out of bounds, and nothing is made`() {
        val out = shouldThrow<OutOfBounds> { atRoot().fileIn(root.resolve(".ps/git-credentials"), creating = true) }

        out.reason shouldContain "none of the names"
        namesIn(root).shouldBeEmpty()
    }

    @Test
    fun `a path that climbs back out is out of bounds, though it starts inside`() {
        val out = shouldThrow<OutOfBounds> { atRoot().fileIn(root.resolve("log/../.ps/git-credentials"), true) }

        out.reason shouldContain "names . or .."
    }

    @Test
    fun `a path outside the repository is out of bounds`() {
        val out = shouldThrow<OutOfBounds> { atRoot().fileIn(outside.resolve("log/submissions.jsonl"), false) }

        out.reason shouldBe "it lies outside the records repository"
    }

    /** What a junction answers on Windows: listed by its parent, and resolving somewhere else. */
    @Test
    fun `a directory whose real path is another is out of bounds`() {
        Files.createDirectories(root.resolve("log"))
        val elsewhere = DiskAnswers(realPathOf = { if (it.endsWith("log")) outside else it.toRealPath() })

        val out = shouldThrow<OutOfBounds> {
            RecordBound.underRoot(root, setOf("log"), elsewhere).fileIn(root.resolve("log/x.jsonl"), creating = false)
        }

        out.reason shouldContain "log resolves to another path"
    }

    @Test
    fun `a path is said relative to the real root, with forward slashes on every platform`() {
        problems().relative(realRoot().resolve("problems/1-x/README.md")) shouldBe "problems/1-x/README.md"
    }

    /**
     * A directory another writer made between the look and the make is no failure (#386): the caller judges what is
     * there after, so a link that won that race is still refused. Any other failure to make one is thrown.
     */
    @Test
    fun `a directory made meanwhile is no failure to make, and any other failure is`() {
        val made = Files.createDirectory(root.resolve("made-meanwhile"))

        shouldNotThrowAny { createdOrThere(made) }
        shouldThrow<NoSuchFileException> { createdOrThere(root.resolve("no-parent/child")) }
    }

    private fun problems() = RecordBound.underProblems(RecordLayout(root))

    private fun atRoot() = RecordBound.underRoot(root, setOf("log"))

    private fun realRoot(): Path = root.toRealPath()
}
