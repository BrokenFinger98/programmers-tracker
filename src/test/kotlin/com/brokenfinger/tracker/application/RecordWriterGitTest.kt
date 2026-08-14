package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.adapter.store.RecordRepositoryIgnores
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aRawSessionId
import com.brokenfinger.tracker.support.fixtures.aSettledCapture
import com.brokenfinger.tracker.support.fixtures.anAssembledSession
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The wiring under test: a record that lands is a record that is committed
 * ([[decisions/2026-08-06-wire-git-into-the-pipeline]]).
 *
 * Real git repositories under a [TempDir] and a real bare remote — no mock, no network. The
 * point of these tests is the seam between the writer and git, and a stubbed [GitSync] would
 * only assert that we called something.
 */
class RecordWriterGitTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun openRecordRepository() {
        repo = GitWorkspace(base)
        // What the server does at startup (#126). Without it every assertion below about
        // what a commit carries would also carry `.ps/`, which is the point.
        RecordRepositoryIgnores(repo.root).ensure()
    }

    @Test
    fun `a settled submit is committed once its record is written`() = runBlocking<Unit> {
        writer().write(aSubmit()) shouldNotBe null

        // The level comes from the catalog and renders as a prefix; a problem the catalog
        // does not know drops the bracket rather than inventing a level.
        repo.subjects().single() shouldStartWith "[Lv0] 두 수의 곱 구하기 — WRONG"
        repo.filesInHead() shouldContainExactly listOf(
            "log/submissions.jsonl",
            "problems/120804-두-수의-곱-구하기/attempts/001.raw.jsonl",
        )
    }

    @Test
    fun `a run is recorded but never committed on its own`() = runBlocking<Unit> {
        writer().write(aRun()) shouldNotBe null

        repo.subjects() shouldContainExactly emptyList()
        repo.statusOf("log/submissions.jsonl") shouldBe "?? log/submissions.jsonl"
    }

    @Test
    fun `a pass pushes, and the push carries another problem's pending commits with it`() = runBlocking<Unit> {
        val remote = repo.withRemote()
        val writer = writer()

        writer.write(aSubmit(lessonId = 131528, name = "other"))
        writer.write(aSubmit(fixture = "algorithm-pass.jsonl", name = "pass"))

        // init + the wrong answer + the pass: the trigger is one problem, the effect is the branch.
        repo.subjects(at = remote).size shouldBe 3
    }

    @Test
    fun `a wrong answer commits without pushing — the trigger is a pass`() = runBlocking<Unit> {
        val remote = repo.withRemote()

        writer().write(aSubmit()) shouldNotBe null

        repo.subjects().size shouldBe 2
        repo.subjects(at = remote).size shouldBe 1
    }

    /**
     * #316, asserted where it was measured: **at the remote**.
     *
     * The scoped commit carries the log line and the raw frames, because those are what exists
     * the moment a grading settles. The solution, the statement and the problem page need a
     * network fetch first, so `CodeAttachment` writes them afterwards — and they used to wait for
     * the 23:00 backup. The push a pass triggered therefore contained everything except the thing
     * that passed.
     *
     * The exact directory name is the layout's business; what is under test here is that files
     * appearing after the verdict still leave the machine on the same pass.
     */
    @Test
    fun `the files written after the verdict reach the remote on the same pass`() = runBlocking<Unit> {
        val remote = repo.withRemote()
        val writer = writer()
        val passed = writer.write(aSubmit(fixture = "algorithm-pass.jsonl", name = "pass"))

        repo.write("$PROBLEM/Solution.java", "class Solution {}")
        repo.write("$PROBLEM/statement.md", "the question that was asked")
        repo.filesAtHead(at = remote) shouldNotContain "$PROBLEM/Solution.java"

        writer.attached(passed!!)

        repo.filesAtHead(at = remote) shouldContainAll listOf("$PROBLEM/Solution.java", "$PROBLEM/statement.md")
    }

    /**
     * The push was **added, not moved**. Moving it would make the verdict — which cannot be
     * fetched again — wait on the source, which can; that is the ordering `copiedRawPath` argues
     * against. This is the state while the fetch is still in flight, and the verdict is already up.
     */
    @Test
    fun `the verdict reaches the remote before any fetch has returned`() = runBlocking<Unit> {
        val remote = repo.withRemote()

        writer().write(aSubmit(fixture = "algorithm-pass.jsonl", name = "pass"))

        repo.filesAtHead(at = remote) shouldContain "log/submissions.jsonl"
    }

    /** A deferred attachment — expired session, rate limit — wrote nothing, so there is nothing to add. */
    @Test
    fun `an attachment that wrote nothing adds no commit`() = runBlocking<Unit> {
        repo.withRemote()
        val writer = writer()
        val passed = writer.write(aSubmit(fixture = "algorithm-pass.jsonl", name = "pass"))
        val before = repo.subjects().size

        writer.attached(passed!!)

        repo.subjects().size shouldBe before
    }

    /** The pass is still the trigger. A wrong answer's files keep riding to the daily backup. */
    @Test
    fun `a wrong answer's attached files are not sent up`() = runBlocking<Unit> {
        val remote = repo.withRemote()
        val writer = writer()
        val wrong = writer.write(aSubmit())
        repo.write("$PROBLEM/Solution.java", "class Solution {}")

        writer.attached(wrong!!)

        repo.filesAtHead(at = remote) shouldNotContain "$PROBLEM/Solution.java"
    }

    /**
     * Same contract as [committed]: the record is already durable when this runs, so a broken git
     * must not escape and take the capture path down with it.
     */
    @Test
    fun `a git that cannot run leaves the pass recorded rather than raising`() = runBlocking<Unit> {
        val writer = writer(git = BrokenGitSync)
        val passed = writer.write(aSubmit(fixture = "algorithm-pass.jsonl", name = "pass"))

        writer.attached(passed!!)

        logLines().size shouldBe 1
    }

    @Test
    fun `a record still lands when every git call fails`() = runBlocking<Unit> {
        val written = writer(git = BrokenGitSync).write(aSubmit())

        written shouldNotBe null
        logLines().size shouldBe 1
        repo.subjects() shouldContainExactly emptyList()
    }

    @Test
    fun `what a failed commit left behind is picked up by the next reconciliation`() = runBlocking<Unit> {
        writer(git = BrokenGitSync).write(aSubmit()) shouldNotBe null

        CommandLineGitSync(repo.root).reconcile() shouldBe true

        repo.subjects().single() shouldBe CommandLineGitSync.RECONCILE_MESSAGE
        // Reconciliation is `git add --all`, so this list is the whole answer to "what does
        // the record repository publish". The raw frames for this grading are sitting in
        // `.ps/raw` one directory up from `attempts/`, and the only reason they are absent is
        // the rule `RecordRepositoryIgnores` wrote — which is why `.gitignore` is here (#126).
        repo.filesInHead() shouldContainExactly listOf(
            ".gitignore",
            "log/submissions.jsonl",
            "problems/120804-두-수의-곱-구하기/attempts/001.raw.jsonl",
        )
    }

    // Harness --------------------------------------------------------------------------------

    private fun writer(git: GitSync = CommandLineGitSync(repo.root)): RecordWriter {
        val layout = RecordLayout(repo.root)
        return RecordWriter.of(
            store = JsonlRecordStore.under(repo.root),
            rawLog = FileRawSessionLog.under(repo.root),
            rawAttemptPath = AttemptRawPath(layout::rawAttemptFile),
            recordRoot = repo.root,
            git = git,
            submissionLog = layout.submissionLog(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            writerDispatcher = Dispatchers.Unconfined,
        )
    }

    /**
     * One grading whose frames are already durable, exactly as stage 1 would have left them.
     * The raw directory sits **inside** the record repository, where production puts it
     * (design §5.1, #126) — so these tests are also what proves it never reaches a commit.
     */
    private fun aSubmit(fixture: String = "algorithm-wrong.jsonl", lessonId: Long = 120804, name: String = "wrong") =
        aCapture(fixture, lessonId, name)

    private fun aRun() = aCapture("algorithm-run-pass.jsonl", 120804, "run")

    private fun aCapture(fixture: String, lessonId: Long, name: String) = aSettledCapture(
        session = anAssembledSession(fixture),
        rawSessionId = staged(name),
        lessonId = lessonId,
        frames = listOf("""{"type":"finish","grading":"$name"}"""),
    )

    private fun staged(name: String) = aRawSessionId("$name.jsonl").also {
        FileRawSessionLog.under(repo.root).append(it, """{"type":"finish","grading":"$name"}""")
    }

    private fun logLines(): List<String> =
        Files.readAllLines(repo.root.resolve("log/submissions.jsonl")).filter { it.isNotBlank() }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-04T05:23:01Z")

        /** Where the layout puts lesson 120804 — a stand-in for "wherever the attached files land". */
        const val PROBLEM = "problems/120804-두-수의-곱-구하기"
    }
}

/**
 * Every git call fails, and fails the hardest way a port can: by throwing. The contract says
 * an implementation never does, so this is the case the writer's guard exists for — a record
 * must not be lost to an adapter that breaks its own promise.
 */
private object BrokenGitSync : GitSync {
    override fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean = error("git is unavailable")

    override fun reconcile(): Boolean = error("git is unavailable")

    override fun push(): Boolean = error("git is unavailable")

    override fun hasRemote(): Boolean = true
}
