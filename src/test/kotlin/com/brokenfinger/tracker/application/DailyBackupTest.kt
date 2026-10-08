package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import com.brokenfinger.tracker.adapter.store.FileBackupLog
import com.brokenfinger.tracker.support.fixtures.MovableClock
import com.brokenfinger.tracker.support.fixtures.aGithubShapedToken
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.sealedWhile
import com.brokenfinger.tracker.support.git.GitWorkspace
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Layer test for the daily backup and its catch-up (design §4.6).
 *
 * Nothing waits for 23:00. The schedule is a comparison against an injected clock, so a
 * machine that slept through the hour, a restart the next morning and an evening that already
 * backed up are all reachable by moving the clock — which is the reason the schedule was
 * written as a comparison rather than as a timer.
 *
 * Git is real: a temp repository, a local bare remote, no network. What "backed up" means is
 * that commits actually arrived at the remote, not that a method was called.
 */
class DailyBackupTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace
    private lateinit var remote: Path

    @BeforeEach
    fun openRecordRepository() {
        repo = GitWorkspace(base)
        remote = repo.withRemote()
    }

    @Test
    fun `an evening past the hour backs up when nothing has yet`() {
        repo.write("log/submissions.jsonl", A_RECORD)

        backup(at = EVENING).runIfDue() shouldBe true

        repo.subjects(at = remote).size shouldBe 2
    }

    /** The attempts a pass never pushed are the whole point of the daily run. */
    @Test
    fun `the backup commits what no pass ever pushed, then pushes it`() {
        repo.write("problems/120804/attempts/001.raw.jsonl", A_RECORD)

        backup(at = EVENING).runIfDue() shouldBe true

        repo.subjects(at = remote).first() shouldBe CommandLineGitSync.RECONCILE_MESSAGE
    }

    @Test
    fun `an evening that already backed up does not back up again`() {
        val backup = backup(at = EVENING)
        backup.runIfDue() shouldBe true

        backup.runIfDue() shouldBe false
    }

    /**
     * The catch-up the design calls for: the laptop was asleep at 23:00, so nothing fired.
     * The next start asks whether the hour has been backed up rather than whether it is now.
     */
    @Test
    fun `a backup the machine slept through runs at the next start`() {
        succeededAt(TWO_NIGHTS_AGO)
        repo.write("log/submissions.jsonl", A_RECORD)

        backup(at = NEXT_MORNING).runIfDue() shouldBe true

        repo.subjects(at = remote).size shouldBe 2
    }

    @Test
    fun `a morning start after the night's backup does not repeat it`() {
        succeededAt(LAST_NIGHT)

        backup(at = NEXT_MORNING).runIfDue() shouldBe false
    }

    @Test
    fun `the evening before the hour is not yet due`() {
        succeededAt(LAST_NIGHT)

        backup(at = BEFORE_THE_HOUR).runIfDue() shouldBe false
    }

    /** The persisted instant is what makes the answer survive the restart it has to survive. */
    @Test
    fun `a restart reads the last backup back off disk rather than starting over`() {
        backup(at = EVENING).runIfDue() shouldBe true

        backup(at = EVENING).runIfDue() shouldBe false
    }

    /**
     * A backup that never left the machine is not a backup. Recording it would skip the day,
     * and the day it skipped is the one whose push failed.
     */
    @Test
    fun `a push that could not land leaves the day due`() {
        val local = GitWorkspace(base.resolve("no-remote"))
        local.write("log/submissions.jsonl", A_RECORD)

        val backup = DailyBackup(CommandLineGitSync(local.root), backupLog(), fixedAt(EVENING), zone = SEOUL)

        backup.runIfDue() shouldBe false

        backupLog().lastSuccessAt() shouldBe null
    }

    // A day counts only once its records are committed (#372) --------------------------------
    //
    // The push alone used to decide, so a day whose records stayed uncommitted was logged as backed
    // up, and the next check found nothing due. Each case below leaves a record uncommitted while the
    // push lands.

    /** The content gate refuses to commit a note that carries a token-shaped string (#360). */
    @Test
    fun `a reconciliation the token search refused leaves the day due`() {
        repo.write("log/submissions.jsonl", A_RECORD)
        repo.write("notes/pasted.md", "${aGithubShapedToken()}\n")

        backup(at = EVENING).runIfDue() shouldBe false

        backupLog().lastSuccessAt() shouldBe null
    }

    /** Reconciliation waits out a merge the user left open rather than commit inside it (#360). */
    @Test
    fun `a reconciliation that waits out a merge leaves the day due`() {
        repo.mergeLeftInConflict("notes.md")
        repo.write("log/submissions.jsonl", A_RECORD)

        backup(at = EVENING).runIfDue() shouldBe false

        backupLog().lastSuccessAt() shouldBe null
    }

    /** Another process holds the index for longer than reconciliation's bounded retries. */
    @Test
    fun `a reconciliation the index lock never let through leaves the day due`() {
        repo.write("log/submissions.jsonl", A_RECORD)
        Files.createFile(repo.root.resolve(".git/index.lock"))

        backup(at = EVENING).runIfDue() shouldBe false

        backupLog().lastSuccessAt() shouldBe null
    }

    /**
     * The backup holds the record, not the push: what is already committed still leaves the machine
     * while the day stays due, so a note the gate refuses does not keep a day's attempts at home.
     */
    @Test
    fun `what is committed still goes up while the day stays due`() {
        repo.write("log/submissions.jsonl", A_RECORD)
        repo.git("add", "--all")
        repo.git("commit", "--message", "a submit no pass pushed")
        repo.write("notes/pasted.md", "${aGithubShapedToken()}\n")

        backup(at = EVENING).runIfDue() shouldBe false

        repo.subjects(at = remote).first() shouldBe "a submit no pass pushed"
    }

    /**
     * Since #360 a branch with no commit yet answers a push with "nothing to push", which succeeds. That
     * must not stand for a reconciliation that was refused: the records are on disk and nowhere else.
     */
    @Test
    fun `a branch with no commit yet whose reconciliation was refused is not backed up`() {
        val fresh = GitWorkspace(base.resolve("fresh"))
        fresh.write("log/submissions.jsonl", A_RECORD)
        fresh.write("notes/pasted.md", "${aGithubShapedToken()}\n")

        backup(at = EVENING, root = fresh.root).runIfDue() shouldBe false

        backupLog().lastSuccessAt() shouldBe null
    }

    /**
     * A branch with no commit yet and nothing to commit holds no record anywhere, so there is nothing to
     * back up and the day counts. Kept due, it would be tried at every check until the first record.
     */
    @Test
    fun `a branch with no commit yet and nothing to commit is backed up`() {
        val fresh = GitWorkspace(base.resolve("fresh"))

        backup(at = EVENING, root = fresh.root).runIfDue() shouldBe true

        backupLog().lastSuccessAt() shouldBe EVENING
    }

    /**
     * A held day is tried again, and the reason a record stayed uncommitted is said where it happened, at
     * each try. That the day is held is said once for each scheduled backup, not at every try: a merge
     * left open overnight would otherwise say it at each of them.
     */
    @Test
    fun `a day held back is said once for each scheduled backup`() {
        repo.write("notes/pasted.md", "${aGithubShapedToken()}\n")
        val clock = MovableClock(EVENING)
        val backup = DailyBackup(sync(repo.root), backupLog(), clock, zone = SEOUL)

        val evening = warningsWhile(DailyBackup::class) { ticks(backup, clock, count = 4) } // tried at 0, 1, 3
        clock.now = NEXT_EVENING
        val next = warningsWhile(DailyBackup::class) { ticks(backup, clock, count = 2) } // tried at 0, 1

        evening.single() shouldContain "uncommitted"
        next.single() shouldContain "uncommitted"
        backupLog().lastSuccessAt() shouldBe null
    }

    /**
     * The one exception to the rule above (the review of #389): a directory git cannot open is not seen
     * at all, so reconciliation commits the rest, answers true, and the day is recorded without what that
     * directory holds. Holding the day for it would retry every minute for something only the owner can
     * fix. What keeps it from passing unseen is the warning, said again at each day's backup while git
     * still cannot open it, not once per process.
     */
    @Test
    fun `a directory git cannot open does not hold the day, and each backup says it again`() {
        assumeTrue(keepsPosixPermissions(repo.root), "this test takes a directory's permissions away")
        repo.write(".gitignore", ".ps/\n")
        val sealed = repo.write("problems/120804/attempts/001.raw.jsonl", A_RECORD).parent.parent
        val clock = MovableClock(EVENING)
        val git = CommandLineGitSync(repo.root, clock = clock, waitFor = {})
        val backup = DailyBackup(git, backupLog(), clock, zone = SEOUL)

        val heard = sealedWhile(sealed) {
            assumeTrue(!Files.isReadable(sealed), "a superuser reads it anyway")
            val evening = warningsWhile(CommandLineGitSync::class) { backup.runIfDue() shouldBe true }
            clock.now = NEXT_EVENING
            evening + warningsWhile(CommandLineGitSync::class) { backup.runIfDue() shouldBe true }
        }

        heard.size shouldBe 2
        heard.forEach { it shouldContain "problems/120804/" }
        backupLog().lastSuccessAt() shouldBe NEXT_EVENING
    }

    // What a backup that does not count says, and how often it tries (#390) -------------------

    /**
     * A repository with no remote is a documented way to run, and the boot report and the backup
     * schedule say so once, at INFO. The backup said "could not push" at every check, beside the push's
     * own warning: 2,880 lines a day. It says nothing now, and the day never counts — a backup that never
     * left the machine is not recorded as one. What reconciliation commits is still committed.
     */
    @Test
    fun `a repository with no remote says nothing at any check, and is never backed up`() {
        val remoteLess = GitWorkspace(base.resolve("remote-less"))
        remoteLess.write(".gitignore", ".ps/\n")
        remoteLess.write("log/submissions.jsonl", A_RECORD)
        val clock = MovableClock(EVENING)
        val backup = DailyBackup(sync(remoteLess.root), backupLog(), clock, zone = SEOUL)

        warnedWhile { ticks(backup, clock, count = 10) } shouldContainExactly emptyList()

        backupLog().lastSuccessAt() shouldBe null
        remoteLess.subjects() shouldContainExactly listOf(CommandLineGitSync.RECONCILE_MESSAGE)
    }

    /**
     * A records directory that vanished while the server runs, where git cannot even start. The check
     * threw, from the question that tells "no remote" from "a push that failed" (the review of #399). It
     * answers now, and the day stays due.
     */
    @Test
    fun `a records directory that vanished leaves the day due, and the check does not throw`() {
        val gone = GitWorkspace(base.resolve("gone"))
        val backup = DailyBackup(sync(gone.root), backupLog(), fixedAt(EVENING), zone = SEOUL)
        gone.root.toFile().deleteRecursively()

        backup.runIfDue() shouldBe false

        backupLog().lastSuccessAt() shouldBe null
    }

    /** A push that really failed — a remote whose URL leads nowhere — still says so, and the day stays due. */
    @Test
    fun `a push that could not reach its remote says so, and leaves the day due`() {
        val unreachable = GitWorkspace(base.resolve("unreachable"))
        unreachable.git("remote", "add", "origin", base.resolve("nowhere.git").toString())
        unreachable.write("log/submissions.jsonl", A_RECORD)
        val backup = DailyBackup(sync(unreachable.root), backupLog(), fixedAt(EVENING), zone = SEOUL)

        val heard = warningsWhile(DailyBackup::class) { backup.runIfDue() shouldBe false }

        heard.single() shouldContain "could not push"
        backupLog().lastSuccessAt() shouldBe null
    }

    /**
     * A refusal that stands — here a note the content search refuses — held the day, and every check then
     * ran git, the records repository's hooks and the search again, and said why: 1,440 times a day (#390).
     * The backup tries again a minute after the first failure, then twice as long each time, up to an
     * hour: seven tries in the first two hours, where there were 120.
     */
    @Test
    fun `a held backup is tried again on a backoff, not at every check`() {
        repo.write("notes/pasted.md", "${aGithubShapedToken()}\n")
        val clock = MovableClock(EVENING)
        val backup = DailyBackup(sync(repo.root), backupLog(), clock, zone = SEOUL)

        val heard = warnedWhile { ticks(backup, clock, count = 120) }

        heard.count { "git reconcile refused" in it } shouldBe 7
        heard.count { "held back" in it } shouldBe 1
        backupLog().lastSuccessAt() shouldBe null
    }

    /** The owner's fix is picked up by the next try: within the hour, at the latest. */
    @Test
    fun `a fix is picked up by the next try`() {
        val note = repo.write("notes/pasted.md", "${aGithubShapedToken()}\n")
        val clock = MovableClock(EVENING)
        val backup = DailyBackup(sync(repo.root), backupLog(), clock, zone = SEOUL)
        ticks(backup, clock, count = 70) // tried at 0, 1, 3, 7, 15, 31 and 63 minutes; the next try is at 123
        Files.writeString(note, "a note with no token in it\n")

        ticks(backup, clock, count = 53)
        backupLog().lastSuccessAt() shouldBe null

        backup.runIfDue() shouldBe true
        backupLog().lastSuccessAt() shouldBe EVENING.plus(Duration.ofMinutes(123))
    }

    /**
     * A push that really failed is tried again on the same backoff, said at each try, and lands once it can.
     * What each line says of the next try holds at that try: from the second on, the next is not a minute
     * away (the review of #399).
     */
    @Test
    fun `a push that keeps failing is tried again on the backoff, and lands once it can`() {
        val unreachable = GitWorkspace(base.resolve("unreachable"))
        val arrivesLater = base.resolve("arrives-later.git")
        unreachable.git("remote", "add", "origin", arrivesLater.toString())
        unreachable.write("log/submissions.jsonl", A_RECORD)
        val clock = MovableClock(EVENING)
        val backup = DailyBackup(sync(unreachable.root), backupLog(), clock, zone = SEOUL)

        val heard = warningsWhile(DailyBackup::class) { ticks(backup, clock, count = 16) } // 0, 1, 3, 7, 15
        unreachable.git("init", "--bare", "-b", "main", arrivesLater.toString(), at = base)
        ticks(backup, clock, count = 16) // the next try is at 31

        heard.count { "could not push" in it } shouldBe 5
        heard.forEach { it shouldNotContain "a minute later" }
        backupLog().lastSuccessAt() shouldBe EVENING.plus(Duration.ofMinutes(31))
    }

    /**
     * A backup the machine slept through is tried at the next start, here at 22:05 the next evening, and
     * fails into a backoff that runs past that evening's 23:00. That hour is a new scheduled backup: it is
     * tried, and said, at once.
     */
    @Test
    fun `the next scheduled backup is tried at once, whatever the last one waits for`() {
        repo.write("notes/pasted.md", "${aGithubShapedToken()}\n")
        val clock = MovableClock(LATE_NEXT_EVENING)
        val backup = DailyBackup(sync(repo.root), backupLog(), clock, zone = SEOUL)

        val heard = warnedWhile { ticks(backup, clock, count = 56) } // 22:05 to 23:00

        heard.count { "git reconcile refused" in it } shouldBe 7 // 22:05, :06, :08, :12, :20, :36, and 23:00
        heard.count { "held back" in it } shouldBe 2
    }

    /**
     * Whether the day is still due decides the backoff, not what the try answered (the review of #399). A
     * try that threw set none, so the next check tried again: a backup log whose write failed gave 60
     * pushes and 60 exceptions in an hour.
     */
    @Test
    fun `a try that throws is backed off like any other`() {
        val clock = MovableClock(EVENING)
        val git = PushCounting(sync(repo.root))
        val backup = DailyBackup(git, UnwritableLog, clock, zone = SEOUL)

        val thrown = thrownWhileTicking(backup, clock, count = 60)

        thrown shouldBe 6 // at 0, 1, 3, 7, 15 and 31 minutes
        git.pushes shouldBe 6
    }

    /**
     * A try can answer that it counted while no record of it was written: a state file's write is skipped
     * while the state directory is refused, and said once by the store (#360). The day stays due, and was
     * tried at every check: 60 pushes in an hour (the review of #399).
     */
    @Test
    fun `a try that counted without a record is backed off like any other`() {
        val clock = MovableClock(EVENING)
        val git = PushCounting(sync(repo.root))
        val backup = DailyBackup(git, ForgetfulLog, clock, zone = SEOUL)

        ticks(backup, clock, count = 60)

        git.pushes shouldBe 6
    }

    /**
     * A try that records the day leaves no backoff behind. Should the record go afterwards — a backup log
     * deleted, or one that cannot be read, reads as never backed up — the next check tries at once, as a
     * restart would, rather than wait out a backoff a success had ended.
     */
    @Test
    fun `a recorded try leaves no backoff behind`() {
        val note = repo.write("notes/pasted.md", "${aGithubShapedToken()}\n")
        val clock = MovableClock(EVENING)
        val backup = DailyBackup(sync(repo.root), backupLog(), clock, zone = SEOUL)
        ticks(backup, clock, count = 1) // held at minute 0
        Files.delete(note)
        ticks(backup, clock, count = 1) // recorded at minute 1
        Files.delete(base.resolve("state/backup.json"))

        backup.runIfDue() shouldBe true // at minute 2
    }

    // Harness --------------------------------------------------------------------------------

    /** [count] checks a minute apart, as the schedule makes them, from the clock's time on. */
    private fun ticks(backup: DailyBackup, clock: MovableClock, count: Int) = repeat(count) {
        backup.runIfDue()
        clock.now = clock.now.plus(Duration.ofMinutes(1))
    }

    /** [ticks] that go on past a check that throws, answering how many did. */
    private fun thrownWhileTicking(backup: DailyBackup, clock: MovableClock, count: Int): Int {
        var thrown = 0
        repeat(count) {
            if (runCatching { backup.runIfDue() }.isFailure) thrown++
            clock.now = clock.now.plus(Duration.ofMinutes(1))
        }
        return thrown
    }

    /** What the backup and the git adapter warned about while [action] ran, the backup's lines first. */
    private fun warnedWhile(action: () -> Unit): List<String> {
        var fromGit = emptyList<String>()
        val fromBackup = warningsWhile(DailyBackup::class) {
            fromGit = warningsWhile(CommandLineGitSync::class, action)
        }
        return fromBackup + fromGit
    }

    /**
     * **The zone is named, not inherited.** Every instant below is written in Seoul terms, and
     * this test used to take that zone from `DailyBackup`'s constructor default — so it asserted
     * the default rather than the behaviour, passed on the author's machine, and failed on CI's
     * UTC runners the moment the default changed (#243). A test whose answer depends on where it
     * runs is a test about the machine.
     */
    private fun backup(at: Instant, root: Path = repo.root) =
        DailyBackup(sync(root), backupLog(), fixedAt(at), zone = SEOUL)

    /** Real git that never sleeps between its retries, so a lock that never clears costs no time. */
    private fun sync(root: Path) = CommandLineGitSync(root, waitFor = {})

    /** File-backed on purpose: every test above is really asking what a restart would read. */
    private fun backupLog(): BackupLog = FileBackupLog(AtomicStateFile(base.resolve("state/backup.json")))

    private fun succeededAt(instant: Instant) = backupLog().succeededAt(instant)

    private fun fixedAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    private companion object {
        const val A_RECORD = """{"lessonId":120804}"""

        /** Every instant below is a Seoul wall clock, so the backup under test is given that zone. */
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** 2026-08-05, 23:30 in Seoul — half an hour past the scheduled hour. */
        val EVENING: Instant = Instant.parse("2026-08-05T14:30:00Z")

        /** 2026-08-06, 23:30 in Seoul — the evening after [EVENING], past the next scheduled hour. */
        val NEXT_EVENING: Instant = Instant.parse("2026-08-06T14:30:00Z")

        /** 2026-08-06, 22:05 in Seoul — the 5th's backup still due, the 6th's not yet. */
        val LATE_NEXT_EVENING: Instant = Instant.parse("2026-08-06T13:05:00Z")

        /** 2026-08-05, 22:00 in Seoul — an hour short of it. */
        val BEFORE_THE_HOUR: Instant = Instant.parse("2026-08-05T13:00:00Z")

        /** 2026-08-06, 09:00 in Seoul. */
        val NEXT_MORNING: Instant = Instant.parse("2026-08-06T00:00:00Z")

        /** 2026-08-05, 23:02 in Seoul — the night before [NEXT_MORNING], backed up. */
        val LAST_NIGHT: Instant = Instant.parse("2026-08-05T14:02:00Z")

        /** 2026-08-04, 23:02 in Seoul — the last backup before a night that was slept through. */
        val TWO_NIGHTS_AGO: Instant = Instant.parse("2026-08-04T14:02:00Z")
    }
}

/** Real git, with its pushes counted: one for each time the backup tried. */
private class PushCounting(private val git: GitSync) : GitSync by git {
    var pushes = 0
        private set

    override fun push(): Boolean {
        pushes++
        return git.push()
    }
}

/** A backup log whose write fails, as a full disk fails it. */
private object UnwritableLog : BackupLog {
    override fun lastSuccessAt(): Instant? = null

    override fun succeededAt(instant: Instant) = throw IOException("No space left on device")
}

/** A backup log whose write is skipped without a word to its caller, as a refused state directory skips it. */
private object ForgetfulLog : BackupLog {
    override fun lastSuccessAt(): Instant? = null

    override fun succeededAt(instant: Instant) = Unit
}
