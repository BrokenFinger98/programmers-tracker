package com.brokenfinger.tracker.adapter.git

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

        TrackedStateEntries(repo.root).any() shouldBe false
    }

    @Test
    fun `a file tracked under it is answered yes`() {
        repo.write(PushCredential.FILE, "e\n")
        repo.git("add", "--force", PushCredential.FILE)

        TrackedStateEntries(repo.root).any() shouldBe true
    }

    @Test
    fun `the entry itself, tracked as a link, is answered yes`() {
        assumeTrue(canPlantLinksIn(repo.root), "this test makes symbolic links")
        aLink(repo.root.resolve(".ps"), Files.createDirectories(repo.root.resolve("problems/zz")))
        repo.git("add", ".ps")

        TrackedStateEntries(repo.root).any() shouldBe true
    }

    /** Asked in any case: `.PS/x` in the index is the state directory on a volume that folds case. */
    @Test
    fun `an entry in another case is answered yes`() {
        repo.write("decoy", "decoy\n")
        val blob = repo.git("hash-object", "-w", "decoy").trim()
        repo.git("update-index", "--add", "--cacheinfo", "100644,$blob,.PS/x")

        TrackedStateEntries(repo.root).any() shouldBe true
    }

    @Test
    fun `a name that only starts like it is answered no`() {
        repo.write(".ps2/notes.md", "not state\n")
        repo.git("add", ".ps2/notes.md")

        TrackedStateEntries(repo.root).any() shouldBe false
    }

    @Test
    fun `a directory git cannot answer for is answered neither`() {
        val elsewhere = Files.createDirectories(base.resolve("not-a-repository"))

        TrackedStateEntries(elsewhere).any().shouldBeNull()
    }
}
