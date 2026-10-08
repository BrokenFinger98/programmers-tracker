package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/**
 * How every git call of the tracker runs (#360). The environment is the tracker's own: a variable the
 * server happened to be started with must not move git to another repository, index or working tree,
 * or switch off the pathspec magic the exclusions depend on.
 */
class GitProcessTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
        repo.write("notes/today.md", "a note\n")
        repo.git("add", "--all")
        repo.git("commit", "--message", "init")
    }

    @Test
    fun `the inherited environment cannot point git at another repository`() {
        val elsewhere = Files.createDirectories(base.resolve("elsewhere"))

        val answer = run("rev-parse", "--show-toplevel", inherited = mapOf("GIT_DIR" to "$elsewhere/.git"))

        answer.succeeded() shouldBe true
        Path.of(answer.stdout.trim()).toRealPath() shouldBe repo.root.toRealPath()
    }

    @Test
    fun `the inherited environment cannot point git at another working tree`() {
        val elsewhere = Files.createDirectories(base.resolve("elsewhere"))

        val answer = run("rev-parse", "--show-toplevel", inherited = mapOf("GIT_WORK_TREE" to "$elsewhere"))

        Path.of(answer.stdout.trim()).toRealPath() shouldBe repo.root.toRealPath()
    }

    /** Pointed at an empty index, `ls-files` answered that nothing at all is tracked. */
    @Test
    fun `the inherited environment cannot hand git another index`() {
        val empty = base.resolve("empty-index")

        val answer = run("ls-files", "-z", inherited = mapOf("GIT_INDEX_FILE" to "$empty"))

        answer.stdout shouldContain "notes/today.md"
    }

    /** `GIT_LITERAL_PATHSPECS=1` reads every `:(…)` as a file name, so no exclusion excludes anything. */
    @Test
    fun `pathspec magic stays on whatever the inherited environment says`() {
        listOf("GIT_LITERAL_PATHSPECS", "GIT_NOGLOB_PATHSPECS").forEach { switch ->
            val answer = run("ls-files", "--", ":(glob)notes/*.md", inherited = mapOf(switch to "1"))

            answer.stdout.trim() shouldBe "notes/today.md"
        }
    }

    /**
     * Windows will not delete a file that a process still holds open, and a git that has exited can
     * leave a child holding its output: `git push` to a local path runs receive-pack. On Windows CI
     * the push's answer was thrown away for that, and a push was reported as one that "could not run".
     */
    @Test
    fun `an output file that cannot be deleted does not lose the answer`() {
        val process = GitProcess(repo.root, discard = { throw FileSystemException(it.toString()) })

        val answer = process.run(listOf("git", "rev-parse", "--show-toplevel"))

        answer.succeeded() shouldBe true
        Path.of(answer.stdout.trim()).toRealPath() shouldBe repo.root.toRealPath()
    }

    private fun run(vararg args: String, inherited: Map<String, String>): GitResult =
        GitProcess(repo.root, System.getenv() + inherited).run(listOf("git") + args)
}
