package com.brokenfinger.tracker.adapter.store

import ch.qos.logback.classic.Level
import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.logging.loggedWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * The vault's dashboard is **seeded, not generated**, and every test here is about that
 * distinction.
 *
 * The tag notes and each problem's README are derived data and are rewritten whole on every pass,
 * because a stale copy of the records is worse than no copy. A `.base` file is not data — it is a
 * query Obsidian evaluates against frontmatter, so it cannot go stale, and the moment the reader
 * adds a column it stops being ours (#254).
 */
class VaultDashboardTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private val dashboard: Path get() = root.resolve("dashboard.base")

    // A seed is the reader's: never written through a link, and never over one (#361) ----------------

    /**
     * A link standing at a seed is left alone. Following it, the refresh overwrote what it led to whenever the
     * ledger recorded that file's bytes; and a link is something someone made, which a seed never replaces.
     */
    @Test
    fun `a seed that is a link is left alone, and the file it leads to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        val guide = aLink(root.resolve("GUIDE.md"), elsewhere)
        SeedLedger(root, aStateDirectory(root)).record("GUIDE.md", NOT_OURS)

        val heard = warningsWhile(VaultDashboard::class) {
            VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        }

        Files.readString(elsewhere) shouldBe NOT_OURS
        Files.isSymbolicLink(guide) shouldBe true
        heard.single() shouldContain guide.toString()
        heard.single() shouldNotContain elsewhere.toString()
    }

    /** A dangling link read as no seed, and the write created the file it named, wherever that was. */
    @Test
    fun `a seed that is a dangling link is left alone, and nothing is created where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-seed.base")
        aLink(dashboard, nowhere)

        val heard = warningsWhile(VaultDashboard::class) {
            VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.isSymbolicLink(dashboard) shouldBe true
        heard.single() shouldContain dashboard.toString()
    }

    /**
     * A seed is read once, through the bound its writer takes (#387), and only a regular file is read: a FIFO would
     * block the boot for a writer that never comes. So what this pins is that the call returns at all — the timeout
     * fails it if the check is removed — and that what stands there is left alone and said.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a seed that is not a regular file is left alone, said, and not waited on`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a FIFO")
        assumeTrue(madeFifo(dashboard), "no mkfifo on this machine")

        val heard = warningsWhile(RecordReads::class) {
            VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        }

        Files.isRegularFile(dashboard, NOFOLLOW_LINKS) shouldBe false
        heard.single() shouldContain "dashboard.base is not a regular file"
    }

    @Test
    fun `writes the dashboard into a vault that has none`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(dashboard) shouldContain "views:"
    }

    /**
     * The guide moved from the copied template into the seeds (#258) — a template reaches only repositories that do
     * not exist yet, which is how the live vault's front page described five phantom notes for months — and from
     * `README.md` to `GUIDE.md` when the front page became the index (#414). Both twins ship, and the Korean one keeps
     * its translated-from marker so the drift guard still applies at the source.
     */
    @Test
    fun `seeds the guide in both languages alongside the dashboard`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(root.resolve("GUIDE.md")) shouldContain "## Structure"
        Files.readString(root.resolve("GUIDE.ko.md")) shouldContain "translated-from: GUIDE.md@"
    }

    /** The front page is the index's now (#414): nothing here writes a README of either language. */
    @Test
    fun `a fresh repository gets no README from the seeds`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.exists(root.resolve("README.md"), NOFOLLOW_LINKS) shouldBe false
        Files.exists(root.resolve("README.ko.md"), NOFOLLOW_LINKS) shouldBe false
    }

    /** Each seed is judged alone: a vault that kept its guide still gains the dashboard. */
    @Test
    fun `a missing seed is written even when the others exist`() {
        Files.writeString(root.resolve("GUIDE.md"), "mine\n")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(root.resolve("GUIDE.md")) shouldBe "mine\n"
        Files.exists(dashboard) shouldBe true
    }

    // The two READMEs it used to seed (#414) ------------------------------------------------------------

    /**
     * `README.md` was the guide, seeded and refreshed while untouched (#300). Still exactly as a server seeded it, it
     * was never anybody's, so it goes: the index takes the front page, and the guide is `GUIDE.md`.
     */
    @Test
    fun `a README still exactly as this server seeded it is taken away, for the index to take its place`() {
        seededEarlier("README.md", AN_OLDER_GUIDE)

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.exists(root.resolve("README.md"), NOFOLLOW_LINKS) shouldBe false
        Files.readString(root.resolve("GUIDE.md")) shouldContain "## Structure"
    }

    /**
     * The owner's only notice that the guide moved: which file was taken away, and what stands in its place. A
     * line nobody asserts on is a line that can go missing unnoticed — the mutant that dropped it survived.
     */
    @Test
    fun `a README taken away is said, with what stands in its place`() {
        seededEarlier("README.md", AN_OLDER_GUIDE)

        val heard = loggedWhile(VaultDashboard::class, Level.INFO) {
            VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        }

        val said = heard.single { it.startsWith("Took ") }
        said shouldContain root.resolve("README.md").toString()
        said shouldContain "the guide is GUIDE.md"
    }

    /** The rule #254 states: editing is respected forever. One character is an edit. */
    @Test
    fun `a README someone edited is kept as it is`() {
        seededEarlier("README.md", AN_OLDER_GUIDE)
        Files.writeString(root.resolve("README.md"), "$AN_OLDER_GUIDE ")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(root.resolve("README.md")) shouldBe "$AN_OLDER_GUIDE "
    }

    /** Absence is not permission: a vault seeded before the ledger has no record, and its README stays. */
    @Test
    fun `a README nothing recorded is kept as it is`() {
        Files.writeString(root.resolve("README.md"), AN_OLDER_GUIDE)

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(root.resolve("README.md")) shouldBe AN_OLDER_GUIDE
    }

    /** The Korean twin goes by the same rule: untouched, it gives way to `GUIDE.ko.md`. */
    @Test
    fun `a README ko still exactly as this server seeded it gives way to GUIDE ko`() {
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.exists(root.resolve("README.ko.md"), NOFOLLOW_LINKS) shouldBe false
        Files.readString(root.resolve("GUIDE.ko.md")) shouldContain "translated-from: GUIDE.md@"
    }

    @Test
    fun `a README ko someone edited is kept as it is`() {
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)
        Files.writeString(root.resolve("README.ko.md"), "$AN_OLDER_GUIDE ")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(root.resolve("README.ko.md")) shouldBe "$AN_OLDER_GUIDE "
    }

    /** A link is something somebody made, whatever the ledger remembers of the file it leads to: it is left alone. */
    @Test
    fun `a README that is a link is left alone, and so is the file it leads to`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        val readme = aLink(root.resolve("README.md"), elsewhere)
        SeedLedger(root, aStateDirectory(root)).record("README.md", NOT_OURS)

        val heard = warningsWhile(VaultDashboard::class) {
            VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        }

        Files.isSymbolicLink(readme) shouldBe true
        Files.readString(elsewhere) shouldBe NOT_OURS
        heard.single() shouldContain readme.toString()
    }

    // The pair, and what replaces each (#414's review) ----------------------------------------------------

    /**
     * Under its title the older guide links its Korean twin by name, so a README the owner keeps keeps its twin too:
     * retiring the twin alone left their page a dead link (#414's review). The guide here carries that exact line,
     * which [AN_OLDER_GUIDE] does not, and is why the first test of the pair missed it.
     */
    @Test
    fun `a README kept as the owner's keeps the Korean twin it links`() {
        seededEarlier("README.md", A_GUIDE_LINKING_ITS_TWIN)
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)
        Files.writeString(root.resolve("README.md"), "${A_GUIDE_LINKING_ITS_TWIN}My own line.\n")

        ensure()

        Files.readString(root.resolve("README.md")) shouldContain "](README.ko.md)"
        Files.readString(root.resolve("README.ko.md")) shouldBe AN_OLDER_GUIDE
    }

    /**
     * A README the owner wrote from scratch names no twin. Kept as theirs, it held the untouched Korean guide beside
     * `GUIDE.ko.md` forever, and nothing said so (#414's second review): the twin stays only while the README names it.
     */
    @Test
    fun `a README of the owner's own that names no twin lets the untouched twin go`() {
        Files.writeString(root.resolve("README.md"), "# My own front page\n")
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)

        ensure()

        Files.readString(root.resolve("README.md")) shouldBe "# My own front page\n"
        Files.exists(root.resolve("README.ko.md"), NOFOLLOW_LINKS) shouldBe false
    }

    /** A README that cannot be read cannot say it names no twin, so the twin stays. */
    @Test
    fun `a README that cannot be read keeps the untouched twin`() {
        Files.createDirectory(root.resolve("README.md"))
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)

        ensure()

        Files.readString(root.resolve("README.ko.md")) shouldBe AN_OLDER_GUIDE
    }

    /** The reverse: only the twin was edited, so the README goes, the index takes its place, and the twin stays. */
    @Test
    fun `a README goes while its edited Korean twin stays`() {
        seededEarlier("README.md", A_GUIDE_LINKING_ITS_TWIN)
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)
        Files.writeString(root.resolve("README.ko.md"), "$AN_OLDER_GUIDE ")

        ensure()

        Files.exists(root.resolve("README.md"), NOFOLLOW_LINKS) shouldBe false
        Files.readString(root.resolve("README.ko.md")) shouldBe "$AN_OLDER_GUIDE "
    }

    /** Both untouched: the README goes first in the same pass, and with it gone nothing links the twin, which goes. */
    @Test
    fun `an untouched pair goes together in one pass`() {
        seededEarlier("README.md", A_GUIDE_LINKING_ITS_TWIN)
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)

        ensure()

        Files.exists(root.resolve("README.md"), NOFOLLOW_LINKS) shouldBe false
        Files.exists(root.resolve("README.ko.md"), NOFOLLOW_LINKS) shouldBe false
    }

    /** A README that is already the index links no twin: the twin left behind by an earlier pass goes now. */
    @Test
    fun `the Korean twin goes once the README is the index`() {
        RootIndex(RecordLayout(root)).write(emptyList())
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)

        ensure()

        Files.exists(root.resolve("README.ko.md"), NOFOLLOW_LINKS) shouldBe false
    }

    /** What replaces a README must be a regular file, judged without following a link: a link at the guide is none. */
    @Test
    fun `a README stays while a link stands where its guide should be`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        seededEarlier("README.md", AN_OLDER_GUIDE)
        aLink(root.resolve("GUIDE.md"), aFileNotOurs(outside))

        ensure()

        Files.readString(root.resolve("README.md")) shouldBe AN_OLDER_GUIDE
    }

    @Test
    fun `a README stays while a directory stands where its guide should be`() {
        seededEarlier("README.md", AN_OLDER_GUIDE)
        Files.createDirectory(root.resolve("GUIDE.md"))

        ensure()

        Files.readString(root.resolve("README.md")) shouldBe AN_OLDER_GUIDE
    }

    /** Each retired seed has its own guide: the Korean twin waits for `GUIDE.ko.md`, whatever `GUIDE.md` is. */
    @Test
    fun `a Korean twin stays while a directory stands where its guide should be`() {
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)
        Files.createDirectory(root.resolve("GUIDE.ko.md"))

        ensure()

        Files.readString(root.resolve("README.ko.md")) shouldBe AN_OLDER_GUIDE
    }

    @Test
    fun `a Korean twin taken away is said, with what stands in its place`() {
        seededEarlier("README.ko.md", AN_OLDER_GUIDE)

        val heard = loggedWhile(VaultDashboard::class, Level.INFO) { ensure() }

        val said = heard.single { it.startsWith("Took ") }
        said shouldContain root.resolve("README.ko.md").toString()
        said shouldContain "the Korean guide is GUIDE.ko.md"
    }

    /**
     * A retirement that fails is said as one, naming the file; it was said as a seed that could not be written. A FIFO
     * is refused by the read, which never opens one, so the call returns, and the FIFO stays where it is.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO where a README was is not retired, said, and not waited on`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a FIFO")
        assumeTrue(madeFifo(root.resolve("README.md")), "no mkfifo on this machine")
        SeedLedger(root, aStateDirectory(root)).record("README.md", AN_OLDER_GUIDE)

        val heard = warningsWhile(VaultDashboard::class) { ensure() }

        Files.isRegularFile(root.resolve("README.md"), NOFOLLOW_LINKS) shouldBe false
        heard.single() shouldContain "Could not retire ${root.resolve("README.md")}"
    }

    @Test
    fun `a directory where a README was is not retired, and said`() {
        Files.createDirectory(root.resolve("README.md"))
        SeedLedger(root, aStateDirectory(root)).record("README.md", AN_OLDER_GUIDE)

        val heard = warningsWhile(VaultDashboard::class) { ensure() }

        Files.isDirectory(root.resolve("README.md"), NOFOLLOW_LINKS) shouldBe true
        heard.single() shouldContain "Could not retire ${root.resolve("README.md")}"
    }

    private fun ensure() = VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

    /** What an older server wrote as [seed], and recorded writing, the way #300's ledger has done since. */
    private fun seededEarlier(seed: String, content: String) {
        Files.writeString(root.resolve(seed), content)
        SeedLedger(root, aStateDirectory(root)).record(seed, content)
    }

    /**
     * The reason it is seeded by the server rather than shipped in the template: a template is
     * copied once, when the repository is created, so every repository that already exists would
     * never see it. That is the staleness the vault README hit twice on 2026-08-13.
     */
    @Test
    fun `a repository that predates the feature still gets one`() {
        Files.writeString(root.resolve("README.md"), "an older vault\n")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.exists(dashboard) shouldBe true
    }

    /**
     * **The one that matters.** Add a column, change a sort, and the file is yours. A server that
     * rewrote it would silently undo that on the next restart, and the reader would have no way
     * to tell why their view kept resetting.
     */
    @Test
    fun `never overwrites a dashboard the reader has edited`() {
        Files.writeString(dashboard, "views: [] # mine\n")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(dashboard) shouldBe "views: [] # mine\n"
    }

    /**
     * The third state (#300). Absent and edited were the only two considered, which left a file
     * **nobody had touched** frozen at whatever shipped the day the vault was made — and twice on
     * 2026-08-13 an improvement reached the one existing vault only by hand.
     */
    @Test
    fun `refreshes a seed the reader never touched`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        Files.writeString(dashboard, "stale content the server itself wrote")
        SeedLedger(root, aStateDirectory(root)).record("dashboard.base", "stale content the server itself wrote")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(dashboard) shouldContain "views:"
    }

    /**
     * A vault seeded before the ledger existed. It may well be untouched and there is no way to
     * know, so it is left alone — overwriting what a person wrote cannot be undone from here,
     * while a stale file costs a hand-copy that was already the status quo.
     */
    @Test
    fun `leaves a seed alone when nothing recorded what it was written from`() {
        Files.createDirectories(root)
        Files.writeString(dashboard, "from a vault older than the ledger\n")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(dashboard) shouldBe "from a vault older than the ledger\n"
    }

    /** Startup runs on every boot; the second pass must be a no-op, not a second write. */
    @Test
    fun `is idempotent across boots`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        val once = Files.readAllBytes(dashboard)

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readAllBytes(dashboard) shouldBe once
    }

    /**
     * An unwritable record repository is a broken setup and still not a reason to refuse to
     * record: the capture is the thing that cannot be replayed (protocol §11), and a grading lost
     * to a dashboard file would be the wrong trade entirely.
     */
    @Test
    fun `does not throw when the vault cannot be written`() {
        root.resolve("no/such/directory").let { VaultDashboard(it, SeedLedger(it, aStateDirectory(it))) }.ensure()
    }

    /**
     * The reason the shipped file carries no comments at all (#314).
     *
     * **Obsidian rewrites `dashboard.base` the first time the Base view renders** and drops every
     * comment: measured 2026-08-14, `9fc9640…` → `a3967cd…` the moment the owner opened it, 15
     * comment lines to 0, with **no** additions. That put the file one character away from what
     * the ledger recorded, so `isUnchanged` answered "edited" and the vault silently stopped
     * receiving dashboard improvements — for a reader who had edited nothing.
     *
     * The shipped bytes are now Obsidian's own output, which is a fixed point of its transform:
     * deletions only, and nothing left to delete. Re-adding a comment here would look harmless
     * and would re-open the door, so it fails here instead. The explanations those comments
     * carried live in the vault README, which survives being read.
     */
    @Test
    fun `ships a dashboard Obsidian will not rewrite`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readAllLines(dashboard).filter { it.isBlank() || it.trimStart().startsWith("#") }
            .shouldBeEmpty()
    }

    /**
     * The one-way door in the ledger, and the population it stranded (#314).
     *
     * Every vault whose owner ever opened the dashboard already holds today's shipped bytes —
     * Obsidian put them there. Its ledger entry still names the old commented form, so without
     * this the file is "edited" forever and no later improvement can land, even though the two
     * agree exactly.
     *
     * Claiming a file that **is** what we would write can never cost an edit: there is none, and
     * writing would be a no-op.
     */
    @Test
    fun `adopts a seed that already matches what we ship, whatever the ledger says`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        val ours = Files.readString(dashboard)
        SeedLedger(root, aStateDirectory(root)).record("dashboard.base", "what an older version of this server shipped")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        SeedLedger(root, aStateDirectory(root)).isUnchanged("dashboard.base", Files.readAllBytes(dashboard))
            .shouldBeTrue()
        Files.readString(dashboard) shouldBe ours
    }

    /** Adoption is not a licence to overwrite: one character different is still theirs. */
    @Test
    fun `does not adopt a seed that differs by a single character`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        Files.writeString(dashboard, Files.readString(dashboard) + " ")
        SeedLedger(root, aStateDirectory(root)).record("dashboard.base", "something else entirely")

        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        Files.readString(dashboard) shouldEndWith " "
    }

    /**
     * The views are the deliverable, so their absence has to fail here rather than in Obsidian.
     * "Not passed yet" especially: Programmers' own scoreboard only remembers that you eventually
     * solved something, and that view is the reason this repository exists.
     *
     * The names are English because they are **our labels**, not the data — the same line
     * `ProblemReadme` draws when it writes "Passed:" above a Korean problem title (dev rules §12).
     */
    @Test
    fun `ships the views the vault is meant to answer with`() {
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()

        val text = Files.readString(dashboard)
        listOf("Recent", "Not passed yet", "By attempts", "By part", "By language", "Tags")
            .forEach { text shouldContain it }
    }

    private companion object {
        /** Any guide an older server seeded as a README: what matters is that the ledger recorded writing it. */
        const val AN_OLDER_GUIDE = "# ps-records\n\nThe usage guide an older server seeded.\n"

        /** The guide as #258 to #413 seeded it begins: under its title, the link to the Korean twin, word for word. */
        const val A_GUIDE_LINKING_ITS_TWIN = "# ps-records\n\n**[한국어](README.ko.md)**\n\nThe usage guide.\n"
    }
}
