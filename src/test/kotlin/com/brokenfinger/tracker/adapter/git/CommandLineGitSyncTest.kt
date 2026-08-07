package com.brokenfinger.tracker.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Layer test for [CommandLineGitSync], driving **real git repositories** under a [TempDir]
 * and a real bare repository as the push target — no network and no mock.
 *
 * A mock is not an option here. Every failure worth catching is a property of git itself:
 * an `index.lock` another process holds, a non-fast-forward rejection, a branch with no
 * commit yet, and a commit that must carry some dirty paths and not others. A test double
 * would agree with whatever this class believes, which is precisely the wrong oracle.
 *
 * Nothing sleeps: the backoff is injected as a function, so a retry test runs instantly and
 * can assert exactly how many attempts failed (the same shape `CableChannelSubscriber` uses).
 */
class CommandLineGitSyncTest {
    @TempDir
    lateinit var base: Path

    private lateinit var root: Path

    @BeforeEach
    fun initRecordRepository() {
        root = Files.createDirectories(base.resolve("records"))
        git("init", "-b", "main")
        git("config", "user.email", "test@example.invalid")
        git("config", "user.name", "Tracker Test")
        // Overrides whatever the developer's global config says, so signing cannot fail the test.
        git("config", "commit.gpgsign", "false")
    }

    @Test
    fun `a submit becomes one commit, subject-formatted as the design requires`() {
        val solution = written("problems/120804/Solution.java", CODE)

        sync().commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe true

        subjects() shouldContainExactly listOf("[Lv0] 두 수의 곱 구하기 — WRONG (1/1, attempt 2)")
    }

    @Test
    fun `a run is never a commit of its own — it waits for the next submit`() {
        val solution = written("problems/120804/Solution.java", CODE)

        sync().commitSubmission(aWrongSubmit(action = GradingAction.RUN), listOf(solution)) shouldBe true

        subjects() shouldContainExactly emptyList()
        statusOf("problems/120804/Solution.java") shouldBe "?? problems/120804/Solution.java"
    }

    @Test
    fun `a submit commit carries the paths it means to carry, not whatever else is dirty`() {
        val solution = written("problems/120804/Solution.java", CODE)
        val unrelated = written("notes.md", "a note the user was writing")
        // Staged by someone else — an editor's git integration does exactly this.
        git("add", "--", "notes.md")

        sync().commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe true

        filesInHead() shouldContainExactly listOf("problems/120804/Solution.java")
        statusOf(unrelated.fileName.toString()) shouldBe "A  notes.md"
    }

    @Test
    fun `committing the same submit again does nothing — the tree is already clean`() {
        val solution = written("problems/120804/Solution.java", CODE)
        val sync = sync()

        sync.commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe true
        sync.commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe true

        subjects().size shouldBe 1
    }

    @Test
    fun `reconciliation commits whatever is uncommitted, including a repository with no commit yet`() {
        written("problems/120804/Solution.java", CODE)
        written("log/submissions.jsonl", """{"lessonId":120804}""")

        sync().reconcile() shouldBe true

        subjects() shouldContainExactly listOf(CommandLineGitSync.RECONCILE_MESSAGE)
        filesInHead() shouldContainExactly listOf("log/submissions.jsonl", "problems/120804/Solution.java")
    }

    @Test
    fun `reconciliation is safe to repeat and does nothing on a clean tree`() {
        written("problems/120804/Solution.java", CODE)
        val sync = sync()

        repeat(3) { sync.reconcile() shouldBe true }

        subjects().size shouldBe 1
    }

    @Test
    fun `a commit blocked by another process's index lock is retried until the lock is released`() {
        val solution = written("problems/120804/Solution.java", CODE)
        val lock = Files.createFile(root.resolve(".git/index.lock"))
        val waits = mutableListOf<Duration>()

        val committed = CommandLineGitSync(root) { delay ->
            waits += delay
            subjects() shouldContainExactly emptyList() // every attempt so far failed on the lock
            if (waits.size == RELEASED_AFTER) Files.delete(lock)
        }.commitSubmission(aWrongSubmit(), listOf(solution))

        committed shouldBe true
        waits.size shouldBe RELEASED_AFTER
        subjects().size shouldBe 1
    }

    @Test
    fun `a lock that never clears is given up on within a bounded schedule, never thrown`() {
        val solution = written("problems/120804/Solution.java", CODE)
        Files.createFile(root.resolve(".git/index.lock"))
        val waits = mutableListOf<Duration>()

        CommandLineGitSync(root) { waits += it }.commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe false

        waits.size shouldBe CommandLineGitSync.MAX_ATTEMPTS - 1
        waits.last() shouldBe CommandLineGitSync.BACKOFF_SCHEDULE.last()
        subjects() shouldContainExactly emptyList()
    }

    @Test
    fun `a failure that is not lock contention is reported at once rather than retried`() {
        val outside = Files.createDirectories(base.resolve("not-a-repository"))
        val solution = Files.writeString(outside.resolve("Solution.java"), CODE)
        val waits = mutableListOf<Duration>()

        CommandLineGitSync(outside) { waits += it }.commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe false

        waits shouldContainExactly emptyList()
    }

    @Test
    fun `a path outside the record repository is never staged`() {
        val stray = Files.writeString(Files.createDirectories(base.resolve("elsewhere")).resolve("x.java"), CODE)

        sync().commitSubmission(aWrongSubmit(), listOf(stray)) shouldBe true

        subjects() shouldContainExactly emptyList()
    }

    @Test
    fun `a pass pushes the branch to the remote`() {
        val remote = remoteInitialised()
        val solution = written("problems/120804/Solution.java", CODE)

        sync().commitSubmission(aPassingSubmit(), listOf(solution)) shouldBe true

        subjects(at = remote).first() shouldBe "[Lv0] 두 수의 곱 구하기 — PASS (1/1, attempt 2, 14m07s)"
    }

    @Test
    fun `a wrong answer commits without pushing — the trigger is a pass`() {
        val remote = remoteInitialised()
        val solution = written("problems/120804/Solution.java", CODE)

        sync().commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe true

        subjects().size shouldBe 2
        subjects(at = remote).size shouldBe 1
    }

    @Test
    fun `a pass on one problem also pushes another problem's pending commits — push moves the branch`() {
        val remote = remoteInitialised()
        val other = written("problems/131528/Solution.sql", "SELECT 1")
        sync().commitSubmission(aWrongSubmit(lessonId = 131528, title = "인기있는 아이스크림"), listOf(other))

        val solution = written("problems/120804/Solution.java", CODE)
        sync().commitSubmission(aPassingSubmit(), listOf(solution)) shouldBe true

        subjects(at = remote).size shouldBe 3
    }

    @Test
    fun `a rejected push is reported and never thrown — the commit still stands`() {
        val remote = remoteInitialised()
        remoteMovedAhead(remote)
        val solution = written("problems/120804/Solution.java", CODE)

        sync().commitSubmission(aPassingSubmit(), listOf(solution)) shouldBe true

        sync().push() shouldBe false
        subjects().size shouldBe 2
    }

    @Test
    fun `a push with nowhere to push is reported, never thrown`() {
        written("problems/120804/Solution.java", CODE)

        sync().reconcile() shouldBe true

        sync().push() shouldBe false
    }

    @Test
    fun `the manual trigger pushes commits no pass ever pushed`() {
        val remote = remoteInitialised()
        val solution = written("problems/120804/Solution.java", CODE)
        sync().commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe true

        sync().push() shouldBe true

        subjects(at = remote).size shouldBe 2
    }

    // A records directory with no repository in it ------------------------------------------

    /**
     * A fresh install has a directory and nothing else. That is a configuration fact rather
     * than a transient failure, so it is answered once and said once: failing every commit for
     * the life of the process and logging each one would bury every other message.
     */
    @Test
    fun `a records directory that is no repository is reported exactly once`() {
        val fresh = Files.createDirectories(base.resolve("fresh-install"))
        val sync = CommandLineGitSync(fresh) { }

        val heard = warningsWhile {
            repeat(3) { sync.commitSubmission(aWrongSubmit(), listOf(fresh.resolve("Solution.java"))) shouldBe false }
            sync.reconcile() shouldBe false
            sync.push() shouldBe false
        }

        heard.size shouldBe 1
        heard.single() shouldContain "not a git repository"
    }

    /**
     * The case the detection comment already claimed to cover and did not (#93): a records
     * directory sitting inside someone else's repository. `rev-parse --git-dir` succeeds
     * from any subdirectory and answers about the *enclosing* repository, so this passed —
     * and `reconcile`'s repo-wide `add --all` then committed that project's unrelated work
     * under our message, ready for the next push.
     */
    @Test
    fun `a records directory nested inside another repository is refused, not adopted`() {
        val enclosing = Files.createDirectories(base.resolve("someone-elses-project"))
        git("init", "-b", "main", at = enclosing)
        Files.writeString(enclosing.resolve("secret-wip.txt"), "the user's unrelated work")
        val records = Files.createDirectories(enclosing.resolve("ps-records"))
        Files.writeString(records.resolve("note.md"), "records live here")

        val sync = CommandLineGitSync(records) { }

        val heard = warningsWhile { sync.reconcile() shouldBe false }
        heard.single() shouldContain "not a git repository"
        // The proof that matters: the enclosing project's work was never touched.
        git("status", "--porcelain", at = enclosing) shouldContain "secret-wip.txt"
    }

    /** Asked once means once: a repository created afterwards is not noticed, and says so. */
    @Test
    fun `a directory that becomes a repository later is still left alone, and quietly`() {
        val fresh = Files.createDirectories(base.resolve("fresh-install"))
        val sync = CommandLineGitSync(fresh) { }
        sync.reconcile() shouldBe false

        git("init", "-b", "main", at = fresh)
        Files.writeString(fresh.resolve("note.md"), "the user fixed it while we were running")

        warningsWhile { sync.reconcile() shouldBe false } shouldContainExactly emptyList()
        git("status", "--porcelain", at = fresh).trim() shouldBe "?? note.md"
    }

    /** What this class said while [action] ran. Logback is what the application logs through. */
    private fun warningsWhile(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(CommandLineGitSync::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        runCatching(action).also { logger.detachAppender(appender) }.getOrThrow()
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private fun sync(waitFor: (Duration) -> Unit = {}) = CommandLineGitSync(root, waitFor)

    private fun aWrongSubmit(
        lessonId: Long = 120804,
        title: String = "두 수의 곱 구하기",
        action: GradingAction = GradingAction.SUBMIT,
    ): SubmissionRecord =
        aSubmissionRecord(lessonId = lessonId, title = title, action = action, verdict = Verdict.WRONG)

    private fun aPassingSubmit(): SubmissionRecord = aSubmissionRecord(verdict = Verdict.PASS)

    private fun written(relative: String, content: String): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        return Files.writeString(file, content)
    }

    /** A bare repository the record repository already pushed its first commit to. */
    private fun remoteInitialised(): Path {
        val remote = base.resolve("remote.git")
        git("init", "--bare", "-b", "main", remote.toString(), at = base)
        git("remote", "add", "origin", remote.toString())
        written("README.md", "# records")
        git("add", "--all")
        git("commit", "--message", "init")
        git("push", "--set-upstream", "origin", "main")
        return remote
    }

    /** Someone else pushed meanwhile, which is what makes the next push a non-fast-forward. */
    private fun remoteMovedAhead(remote: Path) {
        val other = base.resolve("other-clone")
        git("clone", remote.toString(), other.toString(), at = base)
        git("config", "user.email", "other@example.invalid", at = other)
        git("config", "user.name", "Other", at = other)
        git("config", "commit.gpgsign", "false", at = other)
        Files.writeString(other.resolve("elsewhere.md"), "someone else's work")
        git("add", "--all", at = other)
        git("commit", "--message", "elsewhere", at = other)
        git("push", at = other)
    }

    private fun subjects(at: Path = root): List<String> {
        val log = run(listOf("log", "--format=%s"), at)
        if (log.first != 0) return emptyList() // an unborn branch has no log, which is not a failure
        return log.second.trim().lines().filter { it.isNotBlank() }
    }

    private fun filesInHead(): List<String> =
        git("show", "--name-only", "--format=").trim().lines().filter { it.isNotBlank() }.sorted()

    private fun statusOf(relative: String): String = git("status", "--porcelain", "--", relative).trim()

    private fun git(vararg args: String, at: Path = root): String {
        val (code, output) = run(args.toList(), at)
        check(code == 0) { "git ${args.joinToString(" ")} failed with $code: $output" }
        return output
    }

    private fun run(args: List<String>, at: Path): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + args).directory(at.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    private companion object {
        const val CODE = "class Solution {}\n"

        /** Two failed attempts before the external process lets go of the index. */
        const val RELEASED_AFTER = 2
    }
}
