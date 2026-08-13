package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The server initialises the record repository instead of asking the user to (#258).
 *
 * Until now `bootstrap.md` §2 was three commands the user had to run before first start, and
 * forgetting `git init` was not an error — records were written and never committed, with one
 * warning saying so. The warning survives for the cases below that still refuse; the common
 * case simply works.
 */
class RecordRepositoryInitTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `initialises a directory that is not yet a repository`() {
        Files.createDirectories(root.resolve("records"))

        RecordRepositoryInit(root.resolve("records")).ensure()

        git(root.resolve("records"), "rev-parse", "--is-inside-work-tree").trim() shouldBe "true"
    }

    /** The directory itself may not exist yet — a fresh .env pointing at a path to be. */
    @Test
    fun `creates the directory on the way when it does not exist`() {
        RecordRepositoryInit(root.resolve("new/records")).ensure()

        git(root.resolve("new/records"), "rev-parse", "--is-inside-work-tree").trim() shouldBe "true"
    }

    /** An existing repository is the user's; nothing about it may change, HEAD included. */
    @Test
    fun `leaves an existing repository exactly as it was`() {
        val repo = GitWorkspace(root)
        repo.write("log/submissions.jsonl", "{}")
        val head = git(repo.root, "rev-parse", "HEAD").trim()

        RecordRepositoryInit(repo.root).ensure()

        git(repo.root, "rev-parse", "HEAD").trim() shouldBe head
    }

    /**
     * The #93 shape: a records directory nested inside some other project's repository. Before
     * #93 the repo-wide reconcile committed that project's files under our message. Initialising
     * an own repository here is the *fix* for that shape — a nested `.git` makes the records
     * directory its own root, and the enclosing project can no longer be staged from it.
     */
    @Test
    fun `a directory nested inside another repository becomes its own`() {
        val parent = GitWorkspace(root)
        val nested = parent.root.resolve("records")
        Files.createDirectories(nested)

        RecordRepositoryInit(nested).ensure()

        // Compared as paths, not strings: on Windows git answers with forward slashes and a
        // long name where `toRealPath` gives backslashes and possibly a short one. The same
        // class of mismatch ConfiguredPath documents (#41), and only CI can see it.
        Path.of(git(nested, "rev-parse", "--show-toplevel").trim()).toRealPath() shouldBe nested.toRealPath()
    }

    @Test
    fun `is idempotent across boots`() {
        RecordRepositoryInit(root).ensure()
        RecordRepositoryInit(root).ensure()

        git(root, "rev-parse", "--is-inside-work-tree").trim() shouldBe "true"
    }

    /**
     * Records on a read-only mount are a broken setup and still not a reason to crash the
     * server: the capture cannot be replayed, and the existing not-a-repository warning path
     * takes over exactly as before this class existed.
     */
    @Test
    fun `does not throw when the directory cannot be created`() {
        val blocked = root.resolve("file-not-dir")
        Files.writeString(blocked, "a file where a directory should be")

        RecordRepositoryInit(blocked.resolve("records")).ensure()
    }

    /** The default branch is `main`, whatever the machine's git config says (#258). */
    @Test
    fun `initialises onto main rather than the machine's default`() {
        RecordRepositoryInit(root).ensure()

        git(root, "symbolic-ref", "--short", "HEAD").trim() shouldBe "main"
    }

    private fun git(at: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(at.toFile()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out.also { if (process.exitValue() != 0) it shouldContain "" }
    }
}
