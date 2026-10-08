package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.A_LONG_S_STATE_DIRECTORY
import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.foldsTogether
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

        val heard = warningsWhile(CommandLineGitSync::class) {
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

        val heard = warningsWhile(CommandLineGitSync::class) { sync.reconcile() shouldBe false }
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

        warningsWhile(CommandLineGitSync::class) { sync.reconcile() shouldBe false } shouldContainExactly emptyList()
        git("status", "--porcelain", at = fresh).trim() shouldBe "?? note.md"
    }

    // The state directory never enters a reconciliation (#360) --------------------------------

    /**
     * The push token sits at `.ps/git-credentials`, and one `.gitignore` rule was all that kept it out
     * of `add --all`. Git never follows a `.gitignore` that is a link: it warns that it cannot access
     * the file and reads no rules at all. Git stores links, so one can arrive with a clone or a pull,
     * and the next pass would have pushed the token.
     */
    @Test
    fun `a gitignore that is a link does not let reconciliation commit the push token`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".gitignore"), base.resolve("nowhere"))
        aPushTokenIn(root)
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf(".gitignore", "log/submissions.jsonl")
    }

    /**
     * The healthy repository, and the one a literal exclusion broke: with `.ps/` ignored, an argument
     * that names `.ps` makes `add --all` report it as an ignored path and exit 1, so every
     * reconciliation failed (git 2.48.1).
     */
    @Test
    fun `a repository whose gitignore works still reconciles, the state directory left out`() {
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf(".gitignore", "log/submissions.jsonl")
    }

    @Test
    fun `with no gitignore at all, reconciliation still leaves the state directory out`() {
        aPushTokenIn(root)
        written(".ps/raw/recorded/a-run.jsonl", "{}")
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf("log/submissions.jsonl")
    }

    /**
     * The pathspec names `.ps/` in any ASCII case, so the state directory stays out however git spells
     * it (#360). On a case-insensitive volume a case alias is refused before git runs; where `.PS` and
     * `.ps` can stand side by side, as here, the pathspec is what leaves it out.
     */
    @Test
    fun `the state directory is left out in any case`() {
        assumeTrue(!foldsTogether(base, ".PS", ".ps"), "this filesystem folds .PS into .ps")
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written(".PS/raw/a-run.jsonl", RAW_FRAME)
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf(".gitignore", "log/submissions.jsonl")
    }

    /** A timer ticked or a frame landed, and nothing else moved: that is nothing to reconcile, not an empty commit. */
    @Test
    fun `a change under the state directory alone is nothing to reconcile`() {
        written("log/submissions.jsonl", RECORD)
        git("add", "--all")
        git("commit", "--message", "records")
        aPushTokenIn(root)

        sync().reconcile() shouldBe true

        subjects() shouldContainExactly listOf("records")
    }

    /**
     * While `.gitignore` is a link, git prints its warning on every call, `status` included. The
     * warning is not a change: read as one, a clean tree became an empty commit that failed, at
     * every reconciliation.
     */
    @Test
    fun `git's warning about a linked gitignore is not a change to reconcile`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".gitignore"), base.resolve("nowhere"))
        written("log/submissions.jsonl", RECORD)
        git("add", "--all")
        git("commit", "--message", "records")

        sync().reconcile() shouldBe true

        subjects() shouldContainExactly listOf("records")
    }

    /**
     * Git's answer is read from stdout alone, and a status that failed answers nothing there. That is
     * not a clean tree: the reconciliation goes on, fails where git says why, and says so.
     */
    @Test
    fun `a status that fails is reported, never taken for a clean tree`() {
        written(".gitignore", ".ps/\n")
        written("log/submissions.jsonl", RECORD)
        Files.writeString(root.resolve(".git/index"), "not an index")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe false }

        heard.single() shouldContain "git reconcile failed"
    }

    /**
     * Someone forced a state file into the index. A commit that names its paths takes only those, so
     * the file stays staged and goes no further — an editor's staged note is kept out of a submit
     * commit the same way.
     */
    @Test
    fun `a state file staged by hand is not committed by reconciliation`() {
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        git("add", "--force", "--", ".ps/git-credentials")
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf(".gitignore", "log/submissions.jsonl")
        statusOf(".ps/git-credentials") shouldBe "A  .ps/git-credentials"
    }

    /**
     * Reconciliation leaves `.ps/` out either way, but every other rule in `.gitignore` — Finder
     * noise, editor state — stands or falls with the file. So git not ignoring `.ps/` is said, once,
     * with its likely causes, and with nothing read out of any file.
     */
    @Test
    fun `git not ignoring the state directory is said once, with its likely cause`() {
        aPushTokenIn(root)
        written("log/submissions.jsonl", RECORD)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) { repeat(3) { sync.reconcile() shouldBe true } }

        heard.size shouldBe 1
        heard.single() shouldContain "git does not ignore .ps/"
        heard.single() shouldContain "symbolic link"
        heard.single() shouldContain "leaves .ps/ out regardless"
        heard.single() shouldNotContain A_PUSH_CREDENTIAL
    }

    /**
     * The rule the server writes is `.ps/`, which git applies to directories only, so a question
     * about `.ps` before the directory exists is answered "not ignored". A fresh repository has no
     * state directory yet, and its working rule is not broken.
     */
    @Test
    fun `nothing is said when the rule works, even before the state directory exists`() {
        written(".gitignore", ".ps/\n")
        written("log/submissions.jsonl", RECORD)

        warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe true } shouldContainExactly emptyList()
    }

    /** Git answers "not ignored" for a path it tracks, and a file forced into the index is tracked. The rule still works. */
    @Test
    fun `a state file staged by hand does not make a working rule look broken`() {
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        git("add", "--force", "--", ".ps/git-credentials")
        written("log/submissions.jsonl", RECORD)

        warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe true } shouldContainExactly emptyList()
    }

    // Nothing the tracker commits or pushes carries the push token (#360) -----------------------

    /**
     * Another tool committed the state directory — an editor's git plugin running `add -A` under a
     * `.gitignore` git cannot read. The tracker did not make that commit, but its push would send it,
     * so the push looks at every commit it would send before sending any.
     */
    @Test
    fun `a commit another tool made with the push token is never pushed`() {
        val remote = remoteInitialised()
        aPushTokenIn(root)
        written("notes.md", "my note\n")
        git("add", "--all")
        git("commit", "--message", "vault backup (editor plugin)")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "git push refused"
        heard.single() shouldNotContain A_PUSH_CREDENTIAL
        subjects(at = remote) shouldContainExactly listOf("init")
        everythingAt(remote) shouldNotContain A_PUSH_CREDENTIAL
    }

    /**
     * The search is for the token wherever it sits, not for a path. Every other layer guards where the
     * server writes it; this one is what still holds when the token turns up somewhere nobody guarded —
     * here pasted into a note — on any filesystem.
     */
    @Test
    fun `a token in any tracked path is never committed`() {
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("problems/zz/notes.md", "my token is $A_PUSH_CREDENTIAL\n")
        written("log/submissions.jsonl", RECORD)

        val heard = warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe false }

        heard.single() shouldContain "git reconcile refused"
        heard.single() shouldNotContain A_PUSH_CREDENTIAL
        subjects() shouldContainExactly emptyList()
    }

    @Test
    fun `a submit whose files carry the push token is not committed`() {
        aPushTokenIn(root)
        val solution = written("problems/120804/Solution.java", "// $A_PUSH_CREDENTIAL\n$CODE")

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync().commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe false
        }

        heard.single() shouldContain "git commit refused"
        subjects() shouldContainExactly emptyList()
    }

    /**
     * Only a regular file is the credential. A link at its path was put there by someone else — a pull
     * can deliver one — and what it leads to is not ours to search for, so nothing goes out unchecked.
     */
    @Test
    fun `a credential store that is not a regular file refuses every commit and push`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        written(".gitignore", ".ps/\n")
        val target = written("problems/zz/notes.md", "someone's notes\n")
        aLink(root.resolve(PushCredential.FILE), target)
        written("log/submissions.jsonl", RECORD)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync.reconcile() shouldBe false
            sync.push() shouldBe false
        }

        heard.size shouldBe 2
        heard.forEach { it shouldContain "is not a regular file" }
        subjects() shouldContainExactly emptyList()
    }

    @Test
    fun `a healthy repository still commits and pushes with a credential stored`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true
        sync().push() shouldBe true

        subjects(at = remote).first() shouldBe CommandLineGitSync.RECONCILE_MESSAGE
    }

    /**
     * What is searched for is the token itself and the stored line, as fixed strings: never a prefix
     * of the token, and never the user name every GitHub token shares.
     */
    @Test
    fun `part of the token or another x-access-token line is not taken for the credential`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("notes/near.md", "${A_PUSH_CREDENTIAL.dropLast(1)}\nhttps://x-access-token:another@github.com\n")

        sync().reconcile() shouldBe true
        sync().push() shouldBe true

        subjects(at = remote).first() shouldBe CommandLineGitSync.RECONCILE_MESSAGE
    }

    // What the user's own git settings and work must not change (#360) ---------------------------

    /** `status.showUntrackedFiles=no` is the user's to set; read as "clean", it hid every new record. */
    @Test
    fun `a status that hides untracked files does not hide records from reconciliation`() {
        written(".gitignore", ".ps/\n")
        git("add", "--all")
        git("commit", "--message", "init")
        git("config", "status.showUntrackedFiles", "no")
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf("log/submissions.jsonl")
    }

    /**
     * Git refuses a partial commit in the middle of a merge, a cherry-pick, a revert or a rebase — and
     * the staging before it marked every conflict resolved, at every reconciliation. Reconciliation
     * waits instead: it says so once and leaves the user's merge exactly as it was.
     */
    @Test
    fun `reconciliation waits out a merge in progress and leaves it untouched`() {
        written(".gitignore", ".ps/\n")
        written("notes.md", "base\n")
        git("add", "--all")
        git("commit", "--message", "base")
        git("checkout", "-b", "other")
        written("notes.md", "theirs\n")
        git("commit", "--all", "--message", "theirs")
        git("checkout", "main")
        written("notes.md", "ours\n")
        git("commit", "--all", "--message", "ours")
        run(listOf("merge", "other"), root).first shouldBe 1
        written("log/submissions.jsonl", RECORD)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) { repeat(2) { sync.reconcile() shouldBe false } }

        heard.single() shouldContain "in progress"
        statusOf("notes.md") shouldBe "UU notes.md"
        subjects().first() shouldBe "ours"
    }

    /**
     * A record never names a state file, and if it ever did, the submit commit would not carry it: a
     * path whose first segment is `.ps` in any case is dropped before staging, like one outside the
     * repository.
     */
    @Test
    fun `a submit never stages a path under the state directory, in any case`() {
        Files.createDirectory(root.resolve(".ps"))
        val state = written(".ps/timers.json", "{}")
        val aliased = written(".PS/seeds.json", "{}")
        val solution = written("problems/120804/Solution.java", CODE)

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync().commitSubmission(aWrongSubmit(), listOf(state, aliased, solution)) shouldBe true
        }

        filesInHead() shouldContainExactly listOf("problems/120804/Solution.java")
        heard.size shouldBe 2
        heard.forEach { it shouldContain "state directory" }
    }

    // The state directory is the real one, or git is not run at all (#360) ----------------------
    //
    // No credential is stored in these, so the content gate has nothing to search for: what keeps the
    // state that lands in a tracked path out of a commit is the identity check alone.

    /**
     * Git treats an ignored file as expendable, so a pull can delete the state directory and put a
     * tracked link into the tree in its place. Every state write then lands in a path git tracks.
     */
    @Test
    fun `a state directory that is a link into the tree refuses every commit and push`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        written(".gitignore", ".ps/\n")
        aLink(root.resolve(".ps"), Files.createDirectories(root.resolve("problems/zz")))
        written("problems/zz/README.md", "decoy\n")
        git("add", "--all")
        git("commit", "--message", "as a pull delivers it")
        written(".ps/raw/a-run.jsonl", RAW_FRAME)
        val solution = written("problems/120804/Solution.java", CODE)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync.reconcile() shouldBe false
            sync.commitSubmission(aWrongSubmit(), listOf(solution)) shouldBe false
            sync.push() shouldBe false
        }

        heard.size shouldBe 3
        heard.forEach { it shouldContain "is not the tracker's own state directory" }
        subjects() shouldContainExactly listOf("as a pull delivers it")
    }

    /**
     * U+017F folds to `s`, so APFS answers the server's `.ps` with a `.pſ` a clone delivered. Git sees
     * the name on disk, which neither the ignore rule nor the pathspec names — with a healthy
     * `.gitignore`, and silently.
     */
    @Test
    fun `a state directory the filesystem folds from a long s refuses every commit`() {
        assumeTrue(foldsTogether(base, A_LONG_S_STATE_DIRECTORY, ".ps"), "this filesystem does not fold U+017F")
        written(".gitignore", ".ps/\n")
        written("$A_LONG_S_STATE_DIRECTORY/readme", "decoy\n")
        git("add", "--all")
        git("commit", "--message", "as a clone delivers it")
        written(".ps/raw/a-run.jsonl", RAW_FRAME)
        written("log/submissions.jsonl", RECORD)

        val heard = warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe false }

        heard.single() shouldContain "is not the tracker's own state directory"
        subjects() shouldContainExactly listOf("as a clone delivers it")
    }

    /** The same on any case-insensitive volume with `.PS`, where a `.gitignore` git cannot read ignores nothing. */
    @Test
    fun `a state directory the filesystem folds from another case refuses every commit`() {
        assumeTrue(foldsTogether(base, ".PS", ".ps"), "this filesystem keeps .PS and .ps apart")
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".gitignore"), base.resolve("nowhere"))
        written(".PS/readme", "decoy\n")
        git("add", "--all")
        git("commit", "--message", "as a clone delivers it")
        written(".ps/raw/a-run.jsonl", RAW_FRAME)
        written("log/submissions.jsonl", RECORD)

        val heard = warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe false }

        heard.single() shouldContain "is not the tracker's own state directory"
        subjects() shouldContainExactly listOf("as a clone delivers it")
    }

    private fun sync(waitFor: (Duration) -> Unit = {}) = CommandLineGitSync(root, waitFor)

    /** Every commit a repository holds, with its full diff — what a push could ever have delivered there. */
    private fun everythingAt(repository: Path): String =
        git("log", "--all", "--patch", "--format=%H %s", at = repository)

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

    /**
     * The wiring half of #267. [PushCredential] computing the right `-c` is worth nothing if it
     * never reaches a git call, and the old arrangement — `git config` inside the record
     * repository — is exactly what this replaces, so both halves are asserted here: the prefix is
     * on the command, and the repository's own config stays empty.
     */
    @Test
    fun `carries the push credential on every call and writes none into the repository`() {
        val credentials = root.resolve(PushCredential.FILE)
        Files.createDirectories(credentials.parent)
        Files.writeString(credentials, "https://x-access-token:token@github.com\n")

        CommandLineGitSync(root).commandFor(listOf("push")) shouldBe
            listOf("git", "-c", "credential.helper=store --file=$credentials", "push")
        // `run`, not `git`: the key being absent is the assertion, and git exits 1 to say so.
        run(listOf("config", "--local", "--get-all", "credential.helper"), root).second shouldBe ""
    }

    /** A user who never set a token pushes to nothing, and git is invoked exactly as before. */
    @Test
    fun `adds nothing when no credential was ever stored`() {
        CommandLineGitSync(root).commandFor(listOf("status")) shouldBe listOf("git", "status")
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
        const val RECORD = """{"lessonId":120804}"""
        const val RAW_FRAME = """{"type":"frame","marker":"a frame of the server's own state"}"""

        /** Two failed attempts before the external process lets go of the index. */
        const val RELEASED_AFTER = 2
    }
}
