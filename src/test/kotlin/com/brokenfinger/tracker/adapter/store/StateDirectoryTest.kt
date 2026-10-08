package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.adapter.store.StateDirectory.Refusal
import com.brokenfinger.tracker.adapter.store.StateDirectory.Refused
import com.brokenfinger.tracker.adapter.store.StateDirectory.Usable
import com.brokenfinger.tracker.support.fixtures.A_LONG_S_STATE_DIRECTORY
import com.brokenfinger.tracker.support.fixtures.UNTRACK_EVERY_SPELLING
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.foldsTogether
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Whether what answers to `<records>/.ps` is the tracker's own state directory (#360). Git stores
 * links, and a filesystem may fold names, so a clone or a pull can put something else there — and
 * whatever is written into it is then a path git tracks.
 */
class StateDirectoryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `an absent state directory is created as a real directory`() {
        aStateDirectory(root).forGit() shouldBe Usable(root.resolve(".ps"))

        Files.isDirectory(root.resolve(".ps"), LinkOption.NOFOLLOW_LINKS) shouldBe true
    }

    @Test
    fun `a real directory named exactly ps is the state directory`() {
        Files.createDirectory(root.resolve(".ps"))

        aStateDirectory(root).forGit() shouldBe Usable(root.resolve(".ps"))
    }

    /** What a pull can deliver: the ignored directory deleted, and a tracked link into the tree in its place. */
    @Test
    fun `a link in its place is not`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        aLink(root.resolve(".ps"), tracked)

        aStateDirectory(root).forGit() shouldBe Refused(Refusal.NOT_THE_DIRECTORY)
    }

    @Test
    fun `a file in its place is not`() {
        Files.writeString(root.resolve(".ps"), "not a directory\n")

        aStateDirectory(root).forGit() shouldBe Refused(Refusal.NOT_THE_DIRECTORY)
    }

    /** A case-insensitive volume answers `.ps` with `.PS`; the name on disk is what decides. */
    @Test
    fun `a directory the filesystem folds to ps is not`() {
        assumeTrue(foldsTogether(root, ".PS", ".ps"), "this filesystem keeps .PS and .ps apart")
        Files.createDirectory(root.resolve(".PS"))

        aStateDirectory(root).forGit() shouldBe Refused(Refusal.NOT_THE_DIRECTORY)
    }

    /** U+017F folds to `s`, so APFS answers `.ps` with it, and neither the ignore rule nor the pathspec names it. */
    @Test
    fun `a directory with a long s that the filesystem folds to ps is not`() {
        assumeTrue(foldsTogether(root, A_LONG_S_STATE_DIRECTORY, ".ps"), "this filesystem does not fold U+017F")
        Files.createDirectory(root.resolve(A_LONG_S_STATE_DIRECTORY))

        aStateDirectory(root).forGit() shouldBe Refused(Refusal.NOT_THE_DIRECTORY)
    }

    /**
     * The root's own listing decides, on every platform. Where a filesystem folds `.PS` to `.ps`, the
     * listing shows the name on disk, while Linux over a macOS mount answers a folded alias's real path
     * as `.ps` (measured in the tracker's image), so the real-path comparison alone let it through.
     * macOS hides that by answering the real path with the name on disk, and Linux CI cannot fold at
     * all, so the listing is handed in here and the check is pinned wherever the tests run.
     */
    @Test
    fun `a directory the root does not list under exactly that name is not the state directory`() {
        Files.createDirectory(root.resolve(".ps"))

        StateDirectory(root, { false }, listing = { setOf(".PS") }).forGit() shouldBe Refused(Refusal.NOT_THE_DIRECTORY)
    }

    /** A read that failed says nothing about the repository, so it is asked again rather than taken for an answer. */
    @Test
    fun `a records directory that cannot hold one is answered, never thrown`() {
        val notADirectory = Files.writeString(root.resolve("records"), "a file\n")

        val refusal = aStateDirectory(notADirectory).forGit().shouldBeInstanceOf<Refused>().refusal

        refusal shouldBe Refusal.NOT_INSPECTED
        refusal.transient shouldBe true
    }

    // What git tracks there: refused for git and for every writer (#360) ------------------------

    @Test
    fun `a real directory with nothing tracked is usable for git and for writing`() {
        Files.createDirectories(root.resolve(".ps/raw"))

        aStateDirectory(root).forGit() shouldBe Usable(root.resolve(".ps"))
        aStateDirectory(root).forWriting() shouldBe Usable(root.resolve(".ps"))
    }

    /** What the filesystem cannot see: git tracking an entry the server is about to write over. */
    @Test
    fun `anything git tracks there refuses, for good, with how to stop it being tracked`() {
        Files.createDirectory(root.resolve(".ps"))

        aStateDirectory(root, tracked = { true }).forWriting() shouldBe Refused(Refusal.TRACKED)
        Refusal.TRACKED.transient shouldBe false
        Refusal.TRACKED.reason shouldContain UNTRACK_EVERY_SPELLING
    }

    /**
     * Untracked and left on disk, a raw session git delivered reads as the tracker's own (the review of PR #395),
     * so what git put there is deleted first. The server's own files, a credential staged by hand, are only untracked.
     */
    @Test
    fun `the way out of a tracked state directory deletes what git put there before untracking it`() {
        val reason = Refusal.TRACKED.reason

        reason shouldContain "First, and without fail, delete from disk every one git put there rather than this server"
        reason.indexOf("delete from disk") shouldBeLessThan reason.indexOf(UNTRACK_EVERY_SPELLING)
    }

    /**
     * Skipped, the delete was made good only while git's history named the file: a reflog that expired, or a history
     * rewritten with filter-repo or a shallow fetch, let it replay (the review of PR #395, measured). The way out says so.
     */
    @Test
    fun `the way out says why the delete cannot be skipped`() {
        Refusal.TRACKED.reason shouldContain "an expired reflog or a rewritten history"
    }

    /** Removed alone, a linked raw directory takes the sessions behind it off the work list (the review of PR #395). */
    @Test
    fun `a link below the state directory is replaced with what lies behind it, not removed alone`() {
        Refusal.HOLDS_A_LINK.reason shouldContain "moving into it first what lies behind it"
    }

    /** Unknown is not clean for a commit; a capture does not depend on git working. */
    @Test
    fun `git that cannot say refuses git, transiently, and not a writer`() {
        Files.createDirectory(root.resolve(".ps"))
        val state = aStateDirectory(root, tracked = { null })

        state.forGit() shouldBe Refused(Refusal.UNANSWERED)
        Refusal.UNANSWERED.transient shouldBe true
        state.forWriting() shouldBe Usable(root.resolve(".ps"))
    }

    /** A port that throws is answered as one that cannot say, with the exception's kind for why, never its message. */
    @Test
    fun `a history the port threw on is answered with why`() {
        val throwing = object : TrackedState {
            override fun tracksAnything(): Boolean = false

            override fun pathsEverTracked(): TrackedHistory = throw IllegalStateException("never in a reason")
        }

        StateDirectory(root, throwing).pathsEverTracked() shouldBe TrackedHistory.Unanswered("IllegalStateException")
    }

    @Test
    fun `what is not the state directory is refused with how to replace it`() {
        Refusal.NOT_THE_DIRECTORY.reason shouldContain "is not the tracker's own state directory"
        Refusal.NOT_THE_DIRECTORY.reason shouldContain UNTRACK_EVERY_SPELLING
    }

    // Nothing walks the directory; a writer's own path is checked (#360) -----------------------

    /**
     * The walk that looked for a link anywhere below `.ps` read every entry while the tracker's own
     * writers replaced theirs, and an entry gone mid-walk read as a refusal: 444 of 3,000 inspections of
     * a healthy directory (the review of ea1357c). An inspection reads `.ps` itself and asks git, never
     * what lies below, so another writer's rename cannot refuse it.
     */
    @Test
    fun `a healthy directory is never refused while the tracker's own writers replace their files`() {
        val recorded = Files.createDirectories(root.resolve(".ps/raw/recorded"))
        repeat(200) { Files.writeString(recorded.resolve("r$it.jsonl"), "{}\n") }
        val state = aStateDirectory(root)
        val stop = AtomicBoolean()
        val writer = thread { while (!stop.get()) AtomicStateFile(root.resolve(".ps/backup.json")).write("{}") }

        val refused = (1..3_000).map { state.forWriting() }.filterIsInstance<Refused>()

        stop.set(true)
        writer.join()
        refused.shouldBeEmpty()
    }

    /** A link below `.ps` that git does not track — left behind by `git rm --cached` — stops only the writer it would mislead. */
    @Test
    fun `a link below the state directory stops the writer whose path it is on, not git`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        aLink(root.resolve(".ps/raw/recorded"), tracked)
        val state = aStateDirectory(root)

        state.forGit() shouldBe Usable(root.resolve(".ps"))
        state.pathFor("raw") shouldBe Usable(root.resolve(".ps/raw"))
        state.pathFor("raw", "recorded") shouldBe Refused(Refusal.HOLDS_A_LINK)
    }

    @Test
    fun `a writer's directories are created as real ones`() {
        aStateDirectory(root).pathFor("raw", "orphans") shouldBe Usable(root.resolve(".ps/raw/orphans"))

        Files.isDirectory(root.resolve(".ps/raw/orphans"), LinkOption.NOFOLLOW_LINKS) shouldBe true
    }

    /** Nothing is created through a link: the check stops at the first segment that is not a real directory. */
    @Test
    fun `nothing is created below a link`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        aLink(root.resolve(".ps/raw"), tracked)

        aStateDirectory(root).pathFor("raw", "orphans") shouldBe Refused(Refusal.HOLDS_A_LINK)

        Files.exists(tracked.resolve("orphans")) shouldBe false
    }
}
