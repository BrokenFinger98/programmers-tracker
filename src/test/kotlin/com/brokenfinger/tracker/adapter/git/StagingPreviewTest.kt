package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a commit of a scope would add, found without touching the index (#376), driven against **real git**:
 * the oracle for what `add` stages is `add` itself, run after the preview on the real index.
 */
class StagingPreviewTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
        repo.write("kept.md", "kept\n")
        repo.write("changed.md", "old\n")
        repo.write("removed.md", "gone\n")
        repo.git("add", "--all")
        repo.git("commit", "--message", "init")
    }

    @Test
    fun `what staging would add is what add stages, and nothing it removes or leaves`() {
        repo.write("changed.md", "new\n")
        Files.delete(repo.root.resolve("removed.md"))
        repo.write("added.md", "added\n")
        val tree = headTree()

        val previewed = preview().of(EVERYTHING, tree).shouldBeInstanceOf<Introduced>()

        repo.git("add", "--all")
        previewed.listings() shouldBe listOf(listOf(staged("added.md"), staged("changed.md"), "^$tree"))
    }

    /** The search that refuses a commit runs before anything is staged, so a refusal leaves the index as it was. */
    @Test
    fun `the index is left as it was`() {
        repo.write("changed.md", "new\n")
        repo.write("added.md", "added\n")
        val index = repo.root.resolve(".git/index")
        val before = Files.readAllBytes(index)

        preview().of(EVERYTHING, headTree())

        Files.readAllBytes(index) shouldBe before
        repo.git("diff", "--cached", "--name-only").trim() shouldBe ""
    }

    /** `add` stores a link as the path it holds: the file it points at is not what a commit carries. */
    @Test
    fun `a link is introduced as the path it holds, as add stages it`() {
        assumeTrue(canPlantLinksIn(repo.root), "this test makes symbolic links")
        aLink(repo.root.resolve("link.md"), repo.root.resolve("kept.md"))
        val tree = headTree()

        val previewed = preview().of(EVERYTHING, tree).shouldBeInstanceOf<Introduced>()

        repo.git("add", "--all")
        previewed.listings() shouldBe listOf(listOf(staged("link.md"), "^$tree"))
    }

    @Test
    fun `only what is inside the scope is introduced`() {
        repo.write("notes/inside.md", "inside\n")
        repo.write("outside.md", "outside\n")
        val tree = headTree()

        val previewed = preview().of(listOf("notes"), tree).shouldBeInstanceOf<Introduced>()

        repo.git("add", "--all")
        previewed.listings() shouldBe listOf(listOf(staged("notes/inside.md"), "^$tree"))
    }

    /** What the paths it adds are, read from the same answer: a new path, and not one it changes. */
    @Test
    fun `the paths it adds are the names searched`() {
        repo.write("changed.md", "new\n")
        repo.write("added.md", "added\n")
        val previewed = preview().of(EVERYTHING, headTree()).shouldBeInstanceOf<Introduced>()

        previewed.namesSearched(storedAs("added"), NamesHeld.NONE) shouldBe SearchOutcome.FoundInName
        previewed.namesSearched(storedAs("changed"), NamesHeld.NONE) shouldBe SearchOutcome.Clean
    }

    /** Before the first commit everything staging would add is introduced, against the tree with nothing in it. */
    @Test
    fun `on a branch with no commit everything is introduced against the empty tree`() {
        val fresh = GitWorkspace(Files.createDirectories(base.resolve("fresh")))
        fresh.write("first.md", "first\n")

        val previewed = StagingPreview(fresh.root, gitIn(fresh.root)).of(EVERYTHING, EMPTY_TREE)

        fresh.git("add", "--all")
        val first = fresh.git("rev-parse", ":first.md").trim()
        previewed.shouldBeInstanceOf<Introduced>().listings() shouldBe listOf(listOf(first, "^$EMPTY_TREE"))
    }

    /** A failure is git's own answer, so the reason reaches the log in git's words. */
    @Test
    fun `a scope git cannot stage is answered in git's words`() {
        val previewed = preview().of(listOf("no-such-path"), headTree())

        previewed.shouldBeInstanceOf<Preview.Failed>().answer.stderr shouldContain "did not match"
    }

    @Test
    fun `a tree git cannot compare against is answered in git's words`() {
        val previewed = preview().of(EVERYTHING, "f".repeat(40))

        previewed.shouldBeInstanceOf<Preview.Failed>().answer.succeeded() shouldBe false
    }

    @Test
    fun `the copy and its directory are gone afterwards, whatever the answer`() {
        repo.write("added.md", "added\n")
        val scratch = base.resolve("scratch")
        val preview = StagingPreview(repo.root, gitIn(repo.root)) { Files.createDirectories(scratch) }

        preview.of(EVERYTHING, headTree()).shouldBeInstanceOf<Introduced>()
        Files.exists(scratch) shouldBe false
        preview.of(listOf("no-such-path"), headTree()).shouldBeInstanceOf<Preview.Failed>()
        Files.exists(scratch) shouldBe false
    }

    /**
     * Git re-reads a file whose entry is as new as the index itself — a "racily clean" one — since a change
     * within the same tick leaves its size and times as they were. A copy stamped now would make every entry
     * look settled, so the copy keeps the index's own time.
     */
    @Test
    fun `the copy keeps the index's own modification time`() {
        repo.write("added.md", "added\n")
        val index = repo.root.resolve(".git/index")
        val asHandedToAdd = mutableListOf<Boolean>()
        val git = gitIn(repo.root)
        val watching: (List<String>, Map<String, String>) -> GitResult = { args, variables ->
            val copy = variables["GIT_INDEX_FILE"]?.takeIf { args.first() == "add" }
            copy?.let { asHandedToAdd += sameTime(Path.of(it), index) }
            git(args, variables)
        }

        StagingPreview(repo.root, watching).of(EVERYTHING, headTree())

        asHandedToAdd shouldBe listOf(true)
    }

    private fun preview() = StagingPreview(repo.root, gitIn(repo.root))

    private fun gitIn(root: Path): (List<String>, Map<String, String>) -> GitResult {
        val process = GitProcess(root)
        return { args, variables -> process.run(listOf("git") + args, null, variables) }
    }

    private fun headTree(): String = repo.git("rev-parse", "HEAD^{tree}").trim()

    /** What the real index holds for [path], after a real `add`. */
    private fun staged(path: String): String = repo.git("rev-parse", ":$path").trim()

    private fun storedAs(value: String): TokenPatterns = TokenPatterns.of(StoredCredential.Patterns(listOf(value)))

    // Read as the copy is handed to `add`, before git writes it again.
    private fun sameTime(copy: Path, index: Path): Boolean =
        Files.getLastModifiedTime(copy) == Files.getLastModifiedTime(index)

    private companion object {
        val EVERYTHING = listOf(".")

        /** The tree git has with nothing in it, which it knows without one being written. */
        const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    }
}
