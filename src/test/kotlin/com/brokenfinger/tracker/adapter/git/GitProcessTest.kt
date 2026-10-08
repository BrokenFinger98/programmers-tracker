package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

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
     * The tracker reads git's own words: a directory git could not open, an `error:` from a search, the
     * index lock. A git built with translations says them in the server's language — git 2.48.1 here
     * answered this status in Korean under `ko_KR.UTF-8` and as "Auf Branch main" under German, and German
     * translates even the `warning:` prefix (#372). Where git has no translations, or the locale is not
     * installed, it answers in English either way, and this cannot fail.
     */
    @Test
    fun `git answers in English whatever language the server was started in`() {
        listOf("ko_KR.UTF-8" to "ko", "de_DE.UTF-8" to "de").forEach { (locale, language) ->
            val inherited = mapOf("LC_ALL" to locale, "LANG" to locale, "LANGUAGE" to language)

            val answer = run("status", inherited = inherited)

            answer.stdout shouldContain "On branch main"
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

    /**
     * The input was written before the wait began, so a git that stopped reading it held the caller for as
     * long as git ran — past the timeout, or for good (#373). The push gate hands `cat-file` every object id
     * on stdin, far more than a pipe holds. Here a child of git holds the pipe as well and outlives the
     * kill, which a kill that closed the pipe would have waited out.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `input git does not read cannot hold a call past its timeout`() {
        assumeTrue(canPlantLinksIn(base), "this test runs a shell command")
        val process = GitProcess(repo.root, timeout = Duration.ofSeconds(1))
        val started = System.nanoTime()

        val answer = process.run(listOf("git", "-c", "alias.stall=!sleep 10", "stall"), "x".repeat(MORE_THAN_A_PIPE))

        answer.succeeded() shouldBe false
        Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan Duration.ofSeconds(5)
    }

    // An answer read as a stream (#373) ----------------------------------------------------------

    /** `cat-file --batch` prints whole objects, so its answer is read as it comes rather than held. */
    @Test
    fun `an answer can be read as a stream`() {
        val note = repo.git("rev-parse", "HEAD:notes/today.md").trim()

        val answer = GitProcess(repo.root).runReading(listOf("git", "cat-file", "--batch"), "$note\n") {
            it.readAllBytes().decodeToString()
        }

        answer shouldBe "$note blob 7\na note\n\n"
    }

    @Test
    fun `a call that fails hands nothing over to read`() {
        val answer = GitProcess(repo.root).runReading(listOf("git", "cat-file", "--batch", "--no-such-option"), null) {
            it.readAllBytes().decodeToString()
        }

        answer shouldBe null
    }

    /** What a call cut off by the timeout wrote stops anywhere, so it is not read as an answer. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a call cut off by its timeout hands nothing over to read`() {
        assumeTrue(canPlantLinksIn(base), "this test runs a shell command")
        val process = GitProcess(repo.root, timeout = Duration.ofSeconds(1))

        val answer = process.runReading(listOf("git", "-c", "alias.stall=!echo partial; sleep 10", "stall"), null) {
            it.readAllBytes().decodeToString()
        }

        answer shouldBe null
    }

    private fun run(vararg args: String, inherited: Map<String, String>): GitResult =
        GitProcess(repo.root, System.getenv() + inherited).run(listOf("git") + args)

    private companion object {
        /** A megabyte: more than any pipe buffer holds, on any of the three systems CI runs. */
        const val MORE_THAN_A_PIPE = 1 shl 20
    }
}
