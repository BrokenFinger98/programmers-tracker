package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.adapter.git.TrackedStateEntries
import com.brokenfinger.tracker.application.Orphans
import com.brokenfinger.tracker.support.fixtures.ChangingAnswer
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.git.GitWorkspace
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Orphaned frames were written and then never mentioned again (#169). These pin the query
 * that makes them reportable — deliberately a count and a path, never a parse.
 */
class FileRawSessionLogOrphansTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `a repository that has orphaned nothing reports nothing`() {
        log().orphans() shouldBe Orphans.NONE
    }

    @Test
    fun `frames orphaned for a lesson are reported with their count`() {
        val log = log()
        log.orphaned(120802, """{"identifier":"…","message":{"action":"submit","type":"testcase"}}""")
        log.orphaned(120802, """{"identifier":"…","message":{"action":"submit","type":"finish"}}""")

        val orphans = log.orphans().read

        orphans.size shouldBe 1
        orphans.single().lessonId shouldBe 120802
        orphans.single().frames shouldBe 2
        Files.exists(orphans.single().path) shouldBe true
    }

    @Test
    fun `several lessons are reported in lesson order`() {
        val log = log()
        log.orphaned(181946, """{"message":{"action":"submit","type":"finish"}}""")
        log.orphaned(120802, """{"message":{"action":"submit","type":"finish"}}""")

        log.orphans().read.map { it.lessonId } shouldBe listOf(120802L, 181946L)
    }

    /**
     * The orphan directory sits inside the work-list directory, so anything else that lands
     * there must not be counted as a lesson. A stray file reporting as lesson 0 would put a
     * hole in the history that never existed.
     */
    @Test
    fun `a file that is not named after a lesson is not reported as one`() {
        val log = log()
        log.orphaned(120802, """{"message":{"action":"submit","type":"finish"}}""")
        val orphanDirectory = log.orphans().read.single().path.parent
        Files.writeString(orphanDirectory.resolve("notes.txt"), "left by a person\n")
        Files.writeString(orphanDirectory.resolve(".DS_Store"), "\n")

        log.orphans().read.map { it.lessonId } shouldBe listOf(120802L)
    }

    // Read only where a frame could be written, and never a FIFO, a device or a link (#378) ---------

    /**
     * A FIFO under `orphans/` hung the boot and every MCP call, since every answer asks for the orphans: a pulled
     * `1.jsonl -> /proc/self/fd/1` did it in the deployed image (the review of #387, measured). A FIFO is never
     * opened, so the call returns at once and says it passed one over. A thread of its own, because a blocked
     * `open` cannot be interrupted, and the timeout is what fails a regression rather than hanging the suite.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO under orphans is never opened, and the call returns at once`() {
        val orphans = Files.createDirectories(root.resolve(".ps/raw/orphans"))
        assumeTrue(madeFifo(orphans.resolve("1.jsonl")), "this test makes a FIFO")

        log().orphans() shouldBe Orphans(emptyList(), unread = 1, unlisted = false)
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a link to a FIFO under orphans is never followed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        assumeTrue(madeFifo(outside.resolve("fifo")), "this test makes a FIFO")
        aLink(root.resolve(".ps/raw/orphans/1.jsonl"), outside.resolve("fifo"))

        log().orphans() shouldBe Orphans(emptyList(), unread = 1, unlisted = false)
    }

    /** A link to a file outside reported that file's lines as orphaned frames, forging every answer's warning. */
    @Test
    fun `a link to a file outside is not counted`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val forged = Files.writeString(outside.resolve("forged.jsonl"), "{}\n".repeat(40))
        aLink(root.resolve(".ps/raw/orphans/131528.jsonl"), forged)

        log().orphans() shouldBe Orphans(emptyList(), unread = 1, unlisted = false)
    }

    /** The writer refused a linked `orphans/`, and the reader listed through it. */
    @Test
    fun `a linked orphans directory is not listed through`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.createDirectories(root.resolve("problems/zz"))
        Files.writeString(elsewhere.resolve("120802.jsonl"), "{}\n")
        aLink(root.resolve(".ps/raw/orphans"), elsewhere)

        log().orphans() shouldBe Orphans(emptyList(), unread = 0, unlisted = true)
    }

    /** While git tracks anything under `.ps`, an orphans file may be what a pull delivered: counted, never read. */
    @Test
    fun `a tracked orphans file under a refused state directory is not counted`() {
        val orphans = Files.createDirectories(root.resolve(".ps/raw/orphans"))
        Files.writeString(orphans.resolve("131528.jsonl"), "{}\n".repeat(40))

        logGuardedBy(aStateDirectory(root) { true }).orphans() shouldBe
            Orphans(emptyList(), unread = 1, unlisted = false)
    }

    /** As a pull delivers it: committed upstream with `git add --force`, checked out here. Git's answer decides. */
    @Test
    fun `an orphans file git checked out is not counted`(@TempDir base: Path) {
        val repo = GitWorkspace(base)
        repo.write(".gitignore", ".ps/\n")
        repo.write(".ps/raw/orphans/131528.jsonl", "{}\n".repeat(40))
        repo.git("add", ".gitignore")
        repo.git("add", "--force", ".ps/raw/orphans/131528.jsonl")
        repo.git("commit", "--message", "as a pull delivers it")
        val state = StateDirectory(repo.root, TrackedStateEntries(repo.root))

        FileRawSessionLog.under(repo.root, Clock.systemUTC(), state).orphans() shouldBe
            Orphans(emptyList(), unread = 1, unlisted = false)
    }

    /** Untracked, a file a pull delivered reads like the tracker's own; git's history still names it (#377). */
    @Test
    fun `an orphans file git has ever tracked is not counted once untracked`() {
        val orphans = Files.createDirectories(root.resolve(".ps/raw/orphans"))
        Files.writeString(orphans.resolve("131528.jsonl"), "{}\n".repeat(40))
        val git = ChangingAnswer(false, history = setOf("raw/orphans/131528.jsonl"))

        logGuardedBy(aStateDirectory(root, git)).orphans() shouldBe Orphans(emptyList(), unread = 1, unlisted = false)
    }

    /** Every MCP answer asks for the orphans, so what is passed over is said once, by how many, never where. */
    @Test
    fun `orphans passed over are said once, by how many`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".ps/raw/orphans/131528.jsonl"), Files.writeString(outside.resolve("x.jsonl"), "{}\n"))
        val log = log()

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.orphans()
            log.orphans()
        }

        heard.single() shouldContain "1 orphaned-frame file(s) were not read"
        heard.single() shouldNotContain ".ps/"
    }

    @Test
    fun `a refused state directory is said once for the orphans, with why`() {
        val orphans = Files.createDirectories(root.resolve(".ps/raw/orphans"))
        Files.writeString(orphans.resolve("131528.jsonl"), "{}\n")
        val log = logGuardedBy(aStateDirectory(root) { true })

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.orphans()
            log.orphans()
        }

        heard.single() shouldContain "1 orphaned-frame file(s) were counted but not read"
        heard.single() shouldContain StateDirectory.Refusal.TRACKED.reason
    }

    @Test
    fun `an orphans directory that was not listed is said once, with why`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".ps/raw/orphans"), Files.createDirectories(root.resolve("problems/zz")))
        val log = log()

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.orphans()
            log.orphans()
        }

        heard.single() shouldContain "Orphaned frames were not listed"
        heard.single() shouldContain StateDirectory.Refusal.HOLDS_A_LINK.reason
    }

    /** One question to git a call, and none while no orphans file waits: every MCP answer asks. */
    @Test
    fun `git is asked what it has ever tracked once a call, and only when an orphans file waits`() {
        val orphans = Files.createDirectories(root.resolve(".ps/raw/orphans"))
        val git = ChangingAnswer(false)
        val log = logGuardedBy(aStateDirectory(root, git))
        log.orphans() shouldBe Orphans.NONE
        Files.writeString(orphans.resolve("120802.jsonl"), "{}\n")
        Files.writeString(orphans.resolve("181946.jsonl"), "{}\n")

        log.orphans().read.map { it.lessonId } shouldBe listOf(120802L, 181946L)

        git.historyAsked shouldBe 1
    }

    /** Git that cannot say what it has ever tracked: none is read, each is counted, and that is said. */
    @Test
    fun `no orphans file is read while git cannot say what it has ever tracked`() {
        val orphans = Files.createDirectories(root.resolve(".ps/raw/orphans"))
        Files.writeString(orphans.resolve("131528.jsonl"), "{}\n")

        logGuardedBy(aStateDirectory(root, ChangingAnswer(false, history = null))).orphans() shouldBe
            Orphans(emptyList(), unread = 1, unlisted = false)
    }

    @Test
    fun `an orphans file larger than the bound is not read`() {
        val orphans = Files.createDirectories(root.resolve(".ps/raw/orphans"))
        Files.writeString(orphans.resolve("131528.jsonl"), "{}\n".repeat(40))
        val log = FileRawSessionLog(root.resolve(".ps/raw"), guard = aStateDirectory(root), orphanBytes = 100)

        log.orphans() shouldBe Orphans(emptyList(), unread = 1, unlisted = false)
    }

    // Kept, never written through a link (#378) ------------------------------------------------------

    /**
     * A link where a lesson's orphans go made the append fail and the frame was lost (#378). It is held in
     * memory like a refused frame, never written through the link, and written once the link is gone. The link
     * is not replaced: the file is append-only, and a link there stands for history a replacement would drop.
     */
    @Test
    fun `an orphan whose file is a link is kept, never written through, and written once it is gone`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val notOurs = Files.writeString(outside.resolve("not-ours.jsonl"), "")
        val link = aLink(root.resolve(".ps/raw/orphans/120804.jsonl"), notOurs)
        val log = log()

        log.orphaned(120804, """{"lost":1}""")
        Files.readString(notOurs) shouldBe ""
        Files.delete(link)
        log.orphaned(120804, """{"lost":2}""")

        Files.readAllLines(root.resolve(".ps/raw/orphans/120804.jsonl")) shouldBe
            listOf("""{"lost":1}""", """{"lost":2}""")
    }

    /** The orphans held for a lesson whose file is a link wait; the next grading's frames are not stopped by them. */
    @Test
    fun `orphans held behind a link do not stop the next grading's frames`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val git = ChangingAnswer(true)
        val log = logGuardedBy(aStateDirectory(root, git))
        log.orphaned(120804, """{"held":1}""")
        aLink(root.resolve(".ps/raw/orphans/120804.jsonl"), Files.writeString(outside.resolve("x.jsonl"), ""))
        git.answer = false
        val session = log.start(131528)

        log.append(session, """{"n":1}""")

        Files.readAllLines(root.resolve(".ps/raw").resolve(session.value)) shouldBe listOf("""{"n":1}""")
        Files.readString(outside.resolve("x.jsonl")) shouldBe ""
    }

    @Test
    fun `an orphan file that is a link is said once, never where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve(".ps/raw/orphans/120804.jsonl"), Files.writeString(outside.resolve("x.jsonl"), ""))
        val log = log()

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.orphaned(120804, """{"a":1}""")
            log.orphaned(120804, """{"b":2}""")
        }

        heard.single() shouldContain "is not a regular file"
        heard.single() shouldNotContain outside.toString()
    }

    private fun log() = logGuardedBy(aStateDirectory(root))

    private fun logGuardedBy(state: StateDirectory) = FileRawSessionLog.under(root, Clock.systemUTC(), state)
}
