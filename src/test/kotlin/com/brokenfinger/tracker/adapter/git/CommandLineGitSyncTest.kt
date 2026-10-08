package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.adapter.store.FileProblemTimer
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.RecordRepositoryIgnores
import com.brokenfinger.tracker.adapter.store.SeedLedger
import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.A_LONG_S_STATE_DIRECTORY
import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.MovableClock
import com.brokenfinger.tracker.support.fixtures.aFineGrainedShapedToken
import com.brokenfinger.tracker.support.fixtures.aGithubShapedToken
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.foldsTogether
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.sealedWhile
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
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant

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

    /**
     * The owner took a tracked `.ps` link out of the index by hand and put a real directory in its
     * place. A partial commit still takes the entry `.ps` itself from HEAD, finds a directory where a
     * link was tracked, and stops: "'.ps' does not have a commit checked out". So the pathspec leaves
     * out the entry as well as what is under it (#360).
     */
    @Test
    fun `a state directory that replaced a tracked link does not stop reconciliation`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        written(".gitignore", ".ps/\n")
        aLink(root.resolve(".ps"), Files.createDirectories(root.resolve("problems/zz")))
        written("problems/zz/README.md", "decoy\n")
        git("add", "--all")
        git("commit", "--message", "as a pull delivers it")
        git("rm", "--cached", "--quiet", ".ps")
        Files.delete(root.resolve(".ps"))
        Files.createDirectory(root.resolve(".ps"))
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf("log/submissions.jsonl")
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
     * An index git cannot read answers nothing on stdout, and nothing is not a clean tree. Since #360's
     * third round the first to notice is the question of what git tracks under `.ps`, which reads the
     * same index: the reconciliation is refused, and says why.
     */
    @Test
    fun `an index git cannot read is never taken for a clean tree`() {
        written(".gitignore", ".ps/\n")
        written("log/submissions.jsonl", RECORD)
        Files.writeString(root.resolve(".git/index"), "not an index")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe false }

        heard.single() shouldContain "git could not say whether it tracks anything under .ps"
    }

    /**
     * Someone forced a state file into the index, by hand or with a pull. Git tracks it now, so whatever
     * the server writes there is a change any `commit -a` publishes, the credential first of all.
     * Nothing is committed or pushed until it is out of the index, and the warning says how (#360).
     */
    @Test
    fun `a state file staged by hand stops every commit and push until it is unstaged`() {
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        git("add", "--force", "--", PushCredential.FILE)
        written("log/submissions.jsonl", RECORD)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync.reconcile() shouldBe false
            sync.push() shouldBe false
        }

        heard.size shouldBe 2
        heard.forEach { it shouldContain "git rm -r --cached .ps" }
        subjects() shouldContainExactly emptyList()
    }

    /**
     * A pull delivered a store git tracks, holding one letter. Searched for, that letter matched every
     * commit and raised a false alarm about a token. The store is refused for what it is — tracked —
     * and the warning says how to stop that (#360).
     */
    @Test
    fun `a credential store a pull delivered stops every commit and push for what it is`() {
        written(".gitignore", ".ps/\n")
        written(PushCredential.FILE, "e\n")
        git("add", "--all")
        git("add", "--force", "--", PushCredential.FILE)
        git("commit", "--message", "as a pull delivers it")
        written("log/submissions.jsonl", RECORD)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync.reconcile() shouldBe false
            sync.push() shouldBe false
        }

        heard.size shouldBe 2
        heard.forEach { it shouldContain "git tracks files under .ps" }
        heard.forEach { it shouldNotContain "carries" }
    }

    /**
     * `.ps` is the real directory, but a pull put a tracked link inside it, `.ps/raw`, leading into the
     * tree, so the raw frames written through it are tracked paths. The identity check sees only `.ps`;
     * git tracking the link is what refuses (#360).
     */
    @Test
    fun `a link inside the state directory stops every commit and push`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        written(".gitignore", ".ps/\n")
        written("problems/zz/README.md", "decoy\n")
        aLink(root.resolve(".ps/raw"), root.resolve("problems/zz"))
        git("add", "--all")
        git("add", "--force", "--", ".ps/raw")
        git("commit", "--message", "as a pull delivers it")
        written(".ps/raw/a-run.jsonl", RAW_FRAME)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync.reconcile() shouldBe false
            sync.push() shouldBe false
        }

        heard.size shouldBe 2
        heard.forEach { it shouldContain "git tracks files under .ps" }
        subjects() shouldContainExactly listOf("as a pull delivers it")
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
        heard.single() shouldContain "own commits still leave .ps/ out"
        heard.single() shouldNotContain A_PUSH_CREDENTIAL
    }

    /** The case the warning exists for: a `.gitignore` git will not read, because it is a link. */
    @Test
    fun `a gitignore that is a link is said once, with its cause`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".gitignore"), base.resolve("nowhere"))
        written("log/submissions.jsonl", RECORD)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) { repeat(2) { sync.reconcile() shouldBe true } }

        heard.single() shouldContain "git does not ignore .ps/"
        heard.single() shouldContain "symbolic link"
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

    // Nothing the tracker commits or pushes carries the push token (#360) -----------------------

    /**
     * Another tool committed the state directory — an editor's git plugin running `add -A` under a
     * `.gitignore` git cannot read. Git tracks the store now, so the tracker sends nothing at all, and
     * the commit stays on this machine.
     */
    @Test
    fun `a state directory another tool committed stops every push`() {
        val remote = remoteInitialised()
        aPushTokenIn(root)
        written("notes.md", "my note\n")
        git("add", "--all")
        git("commit", "--message", "vault backup (editor plugin)")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "git push refused"
        heard.single() shouldContain "git rm -r --cached .ps"
        subjects(at = remote) shouldContainExactly listOf("init")
        everythingAt(remote) shouldNotContain A_PUSH_CREDENTIAL
    }

    /**
     * The tracker did not make this commit, but its push would send it, so the push looks at every
     * commit it would send before sending any — here a note another tool committed with the token in it.
     */
    @Test
    fun `a commit another tool made with the push token is never pushed`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("notes.md", "my token is $A_PUSH_CREDENTIAL\n")
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

    /**
     * A refusal after staging left the token's file staged, where the next plain `git commit` takes it
     * (the review's M5). The working tree within the commit's scope is searched before anything is
     * staged, so a refused commit leaves the index as it found it.
     */
    @Test
    fun `a refused commit leaves nothing staged`() {
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("problems/zz/notes.md", "my token is $A_PUSH_CREDENTIAL\n")
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe false

        git("diff", "--cached", "--name-only").trim() shouldBe ""
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
     * Only a regular file is the credential. Anything else at its path — here a directory — cannot be
     * searched for, so nothing goes out unchecked.
     */
    @Test
    fun `a credential store that is not a regular file refuses every commit and push`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        git("add", "--all")
        git("commit", "--message", "ignore the state")
        Files.createDirectories(root.resolve(PushCredential.FILE))
        written("log/submissions.jsonl", RECORD)
        val sync = sync()

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync.reconcile() shouldBe false
            sync.push() shouldBe false
        }

        heard.size shouldBe 2
        heard.forEach { it shouldContain "is not a regular file" }
        subjects() shouldContainExactly listOf("ignore the state", "init")
        subjects(at = remote) shouldContainExactly listOf("init")
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
     * The submit path committed inside the user's merge too: its `add` marked the conflicted log
     * resolved, conflict markers and all, and its partial commit then failed (the review's N11). It
     * waits as reconciliation does, and leaves the conflict as it was.
     */
    @Test
    fun `a submit waits out a merge in progress and leaves it untouched`() {
        written(".gitignore", ".ps/\n")
        val log = written("log/submissions.jsonl", "{\"lessonId\":1}\n")
        git("add", "--all")
        git("commit", "--message", "base")
        git("checkout", "--quiet", "-b", "other")
        written("log/submissions.jsonl", "{\"lessonId\":1}\n{\"lessonId\":2}\n")
        git("commit", "--all", "--message", "another machine")
        git("checkout", "--quiet", "main")
        written("log/submissions.jsonl", "{\"lessonId\":1}\n{\"lessonId\":3}\n")
        git("commit", "--all", "--message", "this machine")
        run(listOf("merge", "other"), root).first shouldBe 1
        Files.writeString(log, "{\"lessonId\":4}\n", StandardOpenOption.APPEND)

        val heard = warningsWhile(CommandLineGitSync::class) {
            sync().commitSubmission(aWrongSubmit(), listOf(log)) shouldBe false
        }

        heard.single() shouldContain "in progress"
        statusOf("log/submissions.jsonl") shouldBe "UU log/submissions.jsonl"
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

    // A directory git cannot open (#372) ----------------------------------------------------------

    /**
     * Git reports a directory it cannot open on stderr alone, exits 0 and lists nothing under it. Since
     * #360 dirtiness is read from stdout, so the records in it were left out without a word (the review's
     * F8). It is said once per directory, by the name git gives — never what it holds — and stays a
     * warning: the rest is reconciled, where reading it as a failure failed every check.
     */
    @Test
    fun `a directory git cannot open is said once by name, and the rest is reconciled`() {
        assumeTrue(keepsPosixPermissions(root), "this test takes a directory's permissions away")
        written(".gitignore", ".ps/\n")
        written("log/submissions.jsonl", RECORD)
        val sealed = written("problems/120804/attempts/001.raw.jsonl", RAW_FRAME).parent.parent
        val sync = sync()

        val heard = sealedWhile(sealed) {
            assumeTrue(!Files.isReadable(sealed), "a superuser reads it anyway")
            warningsWhile(CommandLineGitSync::class) { repeat(2) { sync.reconcile() shouldBe true } }
        }

        heard.single() shouldContain "problems/120804/"
        heard.single() shouldNotContain RAW_FRAME
        filesInHead() shouldContainExactly listOf(".gitignore", "log/submissions.jsonl")
    }

    /**
     * Said once per process, a directory that stayed unreadable was never said again, while each day's
     * backup was recorded without what it holds (the review of #389). It is said again on a later date as
     * long as git still cannot open it — and still once a day, however often that day reconciles.
     */
    @Test
    fun `a directory git still cannot open is said again on a later day`() {
        assumeTrue(keepsPosixPermissions(root), "this test takes a directory's permissions away")
        written(".gitignore", ".ps/\n")
        val sealed = written("problems/120804/attempts/001.raw.jsonl", RAW_FRAME).parent.parent
        val clock = MovableClock(Instant.parse("2026-08-05T14:30:00Z"))
        val sync = CommandLineGitSync(root, clock = clock, waitFor = {})

        val heard = sealedWhile(sealed) {
            assumeTrue(!Files.isReadable(sealed), "a superuser reads it anyway")
            val first = warningsWhile(CommandLineGitSync::class) { repeat(2) { sync.reconcile() shouldBe true } }
            clock.now = clock.now.plus(Duration.ofDays(1))
            first + warningsWhile(CommandLineGitSync::class) { repeat(2) { sync.reconcile() shouldBe true } }
        }

        heard.size shouldBe 2
        heard.forEach { it shouldContain "problems/120804/" }
    }

    /**
     * Each directory is said by its own path (the review of #389): one whose name holds a quote, which git
     * prints unescaped inside its own quotes, and one with a space and Hangul, which git prints as they
     * are. A key shared by every directory, or a pattern that stops at a quote, leaves one of them unsaid.
     */
    @Test
    fun `every directory git cannot open is said once, by its own path`() {
        assumeTrue(keepsPosixPermissions(root), "this test takes a directory's permissions away")
        written(".gitignore", ".ps/\n")
        val quoted = written("it's/x", "x\n").parent
        val spaced = written("$SPACED_HANGUL/x.md", "x\n").parent
        val sync = sync()

        val heard = sealedWhile(quoted) {
            sealedWhile(spaced) {
                assumeTrue(!Files.isReadable(quoted), "a superuser reads it anyway")
                warningsWhile(CommandLineGitSync::class) { repeat(2) { sync.reconcile() shouldBe true } }
            }
        }

        heard.size shouldBe 2
        heard.count { "it's/" in it } shouldBe 1
        heard.count { "$SPACED_HANGUL/" in it } shouldBe 1
    }

    /** Git translates the sentence and not the path, and this tool's users run it in Korean. */
    @Test
    fun `a directory git cannot open is said whatever language the server runs in`() {
        assumeTrue(keepsPosixPermissions(root), "this test takes a directory's permissions away")
        written(".gitignore", ".ps/\n")
        val sealed = written("problems/120804/attempts/001.raw.jsonl", RAW_FRAME).parent.parent
        val sync = CommandLineGitSync(root, System.getenv() + KOREAN, waitFor = {})

        val heard = sealedWhile(sealed) {
            assumeTrue(!Files.isReadable(sealed), "a superuser reads it anyway")
            warningsWhile(CommandLineGitSync::class) { sync.reconcile() shouldBe true }
        }

        heard.single() shouldContain "problems/120804/"
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

    /**
     * U1, the review of ea1357c: on APFS a pulled `.pſ/timers.json` lands inside the real `.ps`, while
     * the index records `.pſ/timers.json`. The name on disk stays `.ps`, so the listing passed, and the
     * ASCII-only `icase` question found nothing tracked: the server rewrote the timers, committed them
     * and pushed them.
     */
    @Test
    fun `state a fold of the name tracks is never written, committed or pushed`() {
        assumeTrue(foldsTogether(base, A_LONG_S_STATE_DIRECTORY, ".ps"), "this filesystem does not fold U+017F")
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        val timers = written(".ps/timers.json", "{}")
        git("add", ".gitignore")
        trackedAs("$A_LONG_S_STATE_DIRECTORY/timers.json", timers)
        git("commit", "--message", "as a pull delivers it")
        val state = StateDirectory(root, TrackedStateEntries(root))

        FileProblemTimer.under(root, Clock.systemUTC(), state).startIfAbsent(120804)

        Files.readString(root.resolve(".ps/timers.json")) shouldBe "{}"
        sync().reconcile() shouldBe false
        sync().push() shouldBe false
        git("ls-tree", "-r", "--name-only", "main", at = remote).trim() shouldBe "README.md"
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

    // Anything shaped like a GitHub token, whatever is stored (#360) ----------------------------

    /**
     * The refusal used to say "rotate it", and after rotation the store held the new token, so the old
     * one in an unpushed commit went out (the review's R1). The search also looks for anything shaped
     * like a GitHub token, so a token no longer stored is still found.
     */
    @Test
    fun `a GitHub token no longer stored is still found before a push`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        written(PushCredential.FILE, "https://x-access-token:${aGithubShapedToken('N')}@github.com\n")
        written("notes.md", "the one before: ${aGithubShapedToken('O')}\n")
        git("add", "--all")
        git("commit", "--message", "a note another tool committed")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "revoke the token on GitHub"
        everythingAt(remote) shouldNotContain aGithubShapedToken('O')
    }

    /** With nothing stored at all, a token-shaped string is still not committed. */
    @Test
    fun `a classic GitHub token is refused with nothing stored`() {
        written(".gitignore", ".ps/\n")
        written("notes.md", "${aGithubShapedToken()}\n")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe false }

        heard.single() shouldContain "revoke the token on GitHub"
        heard.single() shouldNotContain aGithubShapedToken()
    }

    /**
     * The `LC_ALL=C` pin's second reason (the review of #389). In a UTF-8 locale macOS's regex stops at a
     * byte that is not UTF-8, so `git grep -E` missed a token after one on the same line, exited 1 and
     * said nothing: measured with Homebrew git 2.48.1 here, and by the review with Apple's git as well. The
     * fixed-string search for the stored value found it either way. In the C locale the token is found.
     * glibc made no difference, so on Linux this passes with the pin or without it.
     */
    @Test
    fun `a token after a byte that is not UTF-8 is never committed, whatever the locale`() {
        written(".gitignore", ".ps/\n")
        val note = root.resolve("notes/cafe.md").also { Files.createDirectories(it.parent) }
        Files.write(note, "caf".toByteArray() + LATIN_1_E_ACUTE + " ${aGithubShapedToken()}\n".toByteArray())
        val sync = CommandLineGitSync(root, System.getenv() + UTF_8_LOCALE, waitFor = {})

        val heard = warningsWhile(CommandLineGitSync::class) { sync.reconcile() shouldBe false }

        heard.single() shouldContain "carries a GitHub token"
        subjects() shouldContainExactly emptyList()
    }

    @Test
    fun `a fine-grained GitHub token is refused with nothing stored`() {
        written(".gitignore", ".ps/\n")
        written("notes.md", "${aFineGrainedShapedToken()}\n")

        sync().reconcile() shouldBe false
    }

    /** A repository with nothing token-shaped in it is untouched: near misses are not tokens. */
    @Test
    fun `strings that only resemble a token are not refused`() {
        written(".gitignore", ".ps/\n")
        written("notes.md", "ghp_short, github_pat_tooShort, x-access-token, ghx_${"A".repeat(36)}\n")

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf(".gitignore", "notes.md")
    }

    // What a push sends, and to where (#360) ----------------------------------------------------

    /**
     * A commit another remote already holds was left out of the search, yet the push to this one sends
     * it. The range is what this remote's own branches lack, not what any remote has.
     */
    @Test
    fun `a commit another remote holds is still searched before it is pushed here`() {
        val remote = remoteInitialised()
        val backup = base.resolve("backup.git")
        git("init", "--bare", "-b", "main", backup.toString(), at = base)
        git("remote", "add", "backup", backup.toString())
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("notes.md", "my token is $A_PUSH_CREDENTIAL\n")
        git("add", "--all")
        git("commit", "--message", "a note")
        git("push", "--quiet", "backup", "main")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "git push refused"
        everythingAt(remote) shouldNotContain A_PUSH_CREDENTIAL
    }

    /**
     * `remote.origin.push` or `push.default=matching` sends branches the search never looked at. The
     * push names its one refspec, the current branch, and nothing else goes.
     */
    @Test
    fun `a push sends the current branch alone, whatever the push settings say`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        git("add", "--all")
        git("commit", "--message", "ignore the state")
        aPushTokenIn(root)
        git("config", "remote.origin.push", "refs/heads/*:refs/heads/*")
        git("config", "push.default", "matching")
        git("checkout", "--quiet", "-b", "drafts")
        written("notes.md", "my token is $A_PUSH_CREDENTIAL\n")
        git("add", "--all")
        git("commit", "--message", "a draft")
        git("checkout", "--quiet", "main")
        written("log/submissions.jsonl", RECORD)
        git("add", "--all")
        git("commit", "--message", "records")

        sync().push() shouldBe true

        val sent = git("for-each-ref", "--format=%(refname)", at = remote).trim().lines()
        sent shouldContainExactly listOf("refs/heads/main")
        everythingAt(remote) shouldNotContain A_PUSH_CREDENTIAL
    }

    /**
     * A replace ref showed the search a clean commit in place of one that carried the token, while the
     * push sent the original: a transfer ignores replacements (measured on 2.48.1 and 2.53.0). Every git
     * call runs with replacements off, so the search sees what a push sends.
     */
    @Test
    fun `a replace ref does not hide a commit from the search`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        aPushTokenIn(root)
        written("notes.md", "my token is $A_PUSH_CREDENTIAL\n")
        git("add", "--all")
        git("commit", "--message", "carries the token")
        val carrying = git("rev-parse", "HEAD").trim()
        val cleanTree = git("rev-parse", "HEAD~1^{tree}").trim()
        val clean = git("commit-tree", cleanTree, "-p", "HEAD~1", "-m", "looks clean").trim()
        git("replace", carrying, clean)

        sync().push() shouldBe false

        everythingAt(remote) shouldNotContain A_PUSH_CREDENTIAL
    }

    /** Before the first commit there is nothing to push, and no failure to report. */
    @Test
    fun `a branch with no commit yet has nothing to push, and says nothing`() {
        written("log/submissions.jsonl", RECORD)

        warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe true } shouldContainExactly emptyList()
    }

    /** No branch, no branch to push to: said, and nothing sent. */
    @Test
    fun `a detached head is not pushed`() {
        val remote = remoteInitialised()
        git("checkout", "--quiet", "--detach")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "detached"
        subjects(at = remote) shouldContainExactly listOf("init")
    }

    // A search that cannot run to the end refuses (#360) ---------------------------------------

    /**
     * `git grep` over a commit whose blob cannot be read says `unable to read` on stderr and exits 1 —
     * the code for "nothing found" (measured on 2.48.1 and 2.53.0). That is not a search that ran to
     * the end, so the push is refused, and said as unsearched.
     */
    @Test
    fun `a blob a push would send that cannot be read stops the push`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        written("notes/today.md", "a note\n")
        git("add", "--all")
        git("commit", "--message", "a record")
        deletedObject("HEAD:notes/today.md")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "did not run to the end"
        subjects(at = remote) shouldContainExactly listOf("init")
    }

    /** A tree that cannot be read fails the search outright (exit 128), and that refuses too. */
    @Test
    fun `a tree a push would send that cannot be read stops the push`() {
        val remote = remoteInitialised()
        written("notes/today.md", "a note\n")
        git("add", "--all")
        git("commit", "--message", "a record")
        deletedObject("HEAD^{tree}")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "did not run to the end"
        subjects(at = remote) shouldContainExactly listOf("init")
    }

    /** Commits that cannot be listed cannot be searched: a remote-tracking ref naming no object stops the push. */
    @Test
    fun `outgoing commits that cannot be listed stop the push`() {
        val remote = remoteInitialised()
        written("notes/today.md", "a note\n")
        git("add", "--all")
        git("commit", "--message", "a record")
        Files.writeString(root.resolve(".git/refs/remotes/origin/main"), "0123456789abcdef0123456789abcdef01234567\n")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "did not run to the end"
        subjects(at = remote) shouldContainExactly listOf("init")
    }

    /**
     * The search after staging reads what `add` made of the files: a clean filter can put a token into
     * the staged blob that the working tree never held, so the search before staging finds nothing.
     */
    @Test
    fun `a token a clean filter puts into what is staged is never committed`() {
        assumeTrue(canPlantLinksIn(root), "this test runs a shell filter")
        written(".gitignore", ".ps/\n")
        git("add", "--all")
        git("commit", "--message", "ignore the state")
        val injected = Files.writeString(base.resolve("injected.txt"), "${aGithubShapedToken()}\n")
        git("config", "filter.inject.clean", "cat '$injected'")
        written(".gitattributes", "notes/*.md filter=inject\n")
        written("notes/today.md", "a note\n")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().reconcile() shouldBe false }

        heard.single() shouldContain "carries a GitHub token"
        subjects() shouldContainExactly listOf("ignore the state")
    }

    // A push that cannot go anywhere, and one that keeps failing (#360) -------------------------

    /**
     * Records kept with no remote are a documented way to run (bootstrap), and the daily backup asks
     * every minute while it is due: each push searched all of history first — 23.4 s for 1,660
     * commits (the review of ea1357c) — and then failed as before. With no remote nothing would be
     * sent, so nothing is searched.
     */
    @Test
    fun `a repository with no remote fails its push without searching`() {
        written(".gitignore", ".ps/\n")
        written("notes/pasted.md", "${aGithubShapedToken()}\n")
        git("add", "--all")
        git("commit", "--message", "history a search would refuse")

        val heard = warningsWhile(CommandLineGitSync::class) { sync().push() shouldBe false }

        heard.single() shouldContain "no remote"
        heard.single() shouldNotContain "token"
    }

    /**
     * A push that keeps failing — a remote that is down — searched the same commits at every attempt.
     * A head already searched clean, for the same remote and the same stored token, is not searched
     * again: a blob that went missing since would have failed a second search.
     */
    @Test
    fun `a head already searched clean is not searched again`() {
        written(".gitignore", ".ps/\n")
        written("notes/today.md", "a note\n")
        git("add", "--all")
        git("commit", "--message", "a record")
        git("remote", "add", "origin", base.resolve("down.git").toString())
        val sync = sync()
        sync.push() shouldBe false
        deletedObject("HEAD:notes/today.md")

        val heard = warningsWhile(CommandLineGitSync::class) { sync.push() shouldBe false }

        heard.single() shouldContain "git push failed"
    }

    /** What was searched is the head it was searched at: a commit made since is searched before it goes. */
    @Test
    fun `a new head is searched again`() {
        val remote = remoteInitialised()
        written(".gitignore", ".ps/\n")
        git("add", "--all")
        git("commit", "--message", "ignore the state")
        val sync = sync()
        sync.push() shouldBe true
        written("notes/pasted.md", "${aGithubShapedToken()}\n")
        git("add", "--all")
        git("commit", "--message", "a token pasted since")

        sync.push() shouldBe false

        everythingAt(remote) shouldNotContain aGithubShapedToken()
    }

    // Every writer of state, the ignore rule and the pathspec agree (#360) ----------------------

    /**
     * One name for the state directory, from every writer through the ignore rule to the pathspec:
     * each writes below [StateDirectory.NAME], git ignores each, and a reconciliation commits none.
     */
    @Test
    fun `every state writer, the ignore rule and the pathspec agree on one directory`() {
        RecordRepositoryIgnores(root).ensure()
        everyStateWriter()
        aPushTokenIn(root)
        written("log/submissions.jsonl", RECORD)

        val state = Files.walk(root.resolve(StateDirectory.NAME)).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList()
        }

        state.size shouldBe 5
        state.forEach { ignoredByGit(it) shouldBe true }
        sync().reconcile() shouldBe true
        filesInHead() shouldContainExactly listOf(".gitignore", "log/submissions.jsonl")
    }

    /**
     * While `.ps` was a tracked link into the tree, every state write landed there, and once the link
     * was removed through git the next reconciliation committed what had piled up: raw frames, timers,
     * the backup marker, the seed ledger (measured by the review). Nothing is written while it is not
     * the real directory, so nothing is left to commit afterwards (#360).
     */
    @Test
    fun `nothing piles up where the state directory pointed while it was a link`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        written(".gitignore", ".ps/\n")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        written("problems/zz/README.md", "decoy\n")
        aLink(root.resolve(".ps"), tracked)
        git("add", "--all")
        git("commit", "--message", "as a pull delivers it")
        everyStateWriter()
        git("rm", "--quiet", ".ps")
        git("commit", "--message", "the link removed through git")
        written("log/submissions.jsonl", RECORD)

        sync().reconcile() shouldBe true

        filesInHead() shouldContainExactly listOf("log/submissions.jsonl")
        Files.list(tracked).use { entries -> entries.map { it.fileName.toString() }.toList() } shouldBe
            listOf("README.md")
    }

    /** One write from each writer of state the server has, each through its own factory. */
    private fun everyStateWriter() {
        val state = StateDirectory(root, TrackedStateEntries(root))
        FileRawSessionLog.under(root, Clock.systemUTC(), state).let { log -> log.append(log.start(120804), RAW_FRAME) }
        FileProblemTimer.under(root, Clock.systemUTC(), state).startIfAbsent(120804)
        FileBackupLog.under(root, state).succeededAt(Instant.EPOCH)
        SeedLedger(root, state).record("dashboard.base", "seeded")
    }

    private fun ignoredByGit(file: Path): Boolean =
        run(listOf("check-ignore", "--quiet", "--no-index", root.relativize(file).joinToString("/")), root).first == 0

    private fun sync(waitFor: (Duration) -> Unit = {}) = CommandLineGitSync(root, waitFor = waitFor)

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

    /**
     * [file] recorded in the index as [path] — what a pull leaves behind when it checks out [path] into a
     * directory the filesystem folds to the one [file] is in.
     */
    private fun trackedAs(path: String, file: Path) {
        val blob = git("hash-object", "-w", "--no-filters", root.relativize(file).toString()).trim()
        git("update-index", "--add", "--cacheinfo", "100644,$blob,$path")
    }

    /**
     * [revision]'s loose object, deleted. Git writes objects read-only, and Windows refuses to delete a
     * read-only file where POSIX only asks about the directory, so the attribute is cleared first.
     */
    private fun deletedObject(revision: String) {
        val file = looseObjectOf(revision)
        file.toFile().setWritable(true)
        Files.delete(file)
    }

    /** Where git keeps [revision]'s object while it is loose, as it is in a repository never packed. */
    private fun looseObjectOf(revision: String): Path {
        val id = git("rev-parse", revision).trim()
        return root.resolve(".git/objects/${id.take(2)}/${id.drop(2)}")
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

        /** The locale a Korean user's server runs in, where git translates what it says. */
        val KOREAN = mapOf("LC_ALL" to "ko_KR.UTF-8", "LANG" to "ko_KR.UTF-8")

        /** An ordinary UTF-8 locale, as the tracker's own image sets it, where git says everything in English. */
        val UTF_8_LOCALE = mapOf("LC_ALL" to "en_US.UTF-8", "LANG" to "en_US.UTF-8")

        /** A directory name with a space and Hangul in it ("note folder"), which git prints unquoted and unescaped. */
        const val SPACED_HANGUL = "노트 폴더"

        /** `é` in Latin-1: one byte that is never valid UTF-8 on its own. */
        const val LATIN_1_E_ACUTE: Byte = 0xE9.toByte()

        /** Two failed attempts before the external process lets go of the index. */
        const val RELEASED_AFTER = 2
    }
}
