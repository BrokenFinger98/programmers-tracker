package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.A_LONG_S_STATE_DIRECTORY
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether git tracks anything under the state directory (#360). A pull can deliver a tracked file
 * there — a credential store holding one letter, a link — and a path git tracks is one any
 * `commit -a` publishes once the server writes to it. Real git, because what git lists is the oracle.
 */
class TrackedStateEntriesTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
    }

    @Test
    fun `nothing tracked under the state directory is answered no`() {
        repo.write("notes.md", "a note\n")
        repo.git("add", "notes.md")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe false
    }

    @Test
    fun `a file tracked under it is answered yes`() {
        repo.write(PushCredential.FILE, "e\n")
        repo.git("add", "--force", PushCredential.FILE)

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    @Test
    fun `the entry itself, tracked as a link, is answered yes`() {
        assumeTrue(canPlantLinksIn(repo.root), "this test makes symbolic links")
        aLink(repo.root.resolve(".ps"), Files.createDirectories(repo.root.resolve("problems/zz")))
        repo.git("add", ".ps")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    /** Asked in any case: `.PS/x` in the index is the state directory on a volume that folds case. */
    @Test
    fun `an entry in another case is answered yes`() {
        repo.write("decoy", "decoy\n")
        val blob = repo.git("hash-object", "-w", "--no-filters", "decoy").trim()
        repo.git("update-index", "--add", "--cacheinfo", "100644,$blob,.PS/x")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    /**
     * `.pſ` — `.p` and U+017F — folds to `.ps` on APFS, so `.pſ/x` in the index is a file inside the
     * real state directory, and an ASCII-only `icase` pathspec never named it (U1, the review of
     * ea1357c). Asked of the index itself, so it holds where nothing folds as well.
     */
    @Test
    fun `an entry under a Unicode case fold of the name is answered yes`() {
        repo.write("decoy", "decoy\n")
        val blob = repo.git("hash-object", "-w", "--no-filters", "decoy").trim()
        repo.git("update-index", "--add", "--cacheinfo", "100644,$blob,$A_LONG_S_STATE_DIRECTORY/x")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    /**
     * Whatever the name, an entry whose first segment is the state directory on disk is under it — a
     * fold no case rule knows, a short name, a link. A tracked link to `.ps` shows it on any filesystem.
     */
    @Test
    fun `an entry the filesystem resolves to the state directory is answered yes`() {
        assumeTrue(canPlantLinksIn(repo.root), "this test makes symbolic links")
        val state = Files.createDirectories(repo.root.resolve(".ps"))
        aLink(repo.root.resolve("elsewhere"), state)
        repo.git("add", "elsewhere")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    @Test
    fun `a name that only starts like it is answered no`() {
        repo.write(".ps2/notes.md", "not state\n")
        repo.git("add", ".ps2/notes.md")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe false
    }

    /**
     * The question reads the repository's own index whatever the server's environment names: pointed
     * at an empty one, it answered that nothing was tracked (#360, the review's ENV).
     */
    @Test
    fun `a tracked state file is found whatever index the environment names`() {
        repo.write(PushCredential.FILE, "e\n")
        repo.git("add", "--force", PushCredential.FILE)
        val environment = System.getenv() + mapOf("GIT_INDEX_FILE" to base.resolve("empty-index").toString())

        TrackedStateEntries(repo.root, environment).tracksAnything() shouldBe true
    }

    @Test
    fun `a directory git cannot answer for is answered neither`() {
        val elsewhere = Files.createDirectories(base.resolve("not-a-repository"))

        TrackedStateEntries(elsewhere).tracksAnything().shouldBeNull()
    }
}
