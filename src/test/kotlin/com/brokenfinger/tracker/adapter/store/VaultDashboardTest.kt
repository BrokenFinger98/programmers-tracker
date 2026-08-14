package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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

    private val dashboard: Path get() = root.resolve("dashboard.base")

    @Test
    fun `writes the dashboard into a vault that has none`() {
        VaultDashboard(root).ensure()

        Files.readString(dashboard) shouldContain "views:"
    }

    /**
     * The README moved from the copied template into the seeds (#258): a template reaches only
     * repositories that do not exist yet, which is how the live vault's front page described
     * five phantom notes for months. Both twins ship, and the Korean one keeps its
     * translated-from marker so the drift guard still applies at the source.
     */
    @Test
    fun `seeds both readme twins alongside the dashboard`() {
        VaultDashboard(root).ensure()

        Files.readString(root.resolve("README.md")) shouldContain "# ps-records"
        Files.readString(root.resolve("README.ko.md")) shouldContain "translated-from: README.md@"
    }

    /** Each seed is judged alone: a vault that kept its README still gains the dashboard. */
    @Test
    fun `a missing seed is written even when the others exist`() {
        Files.writeString(root.resolve("README.md"), "mine\n")

        VaultDashboard(root).ensure()

        Files.readString(root.resolve("README.md")) shouldBe "mine\n"
        Files.exists(dashboard) shouldBe true
    }

    /**
     * The reason it is seeded by the server rather than shipped in the template: a template is
     * copied once, when the repository is created, so every repository that already exists would
     * never see it. That is the staleness the vault README hit twice on 2026-08-13.
     */
    @Test
    fun `a repository that predates the feature still gets one`() {
        Files.writeString(root.resolve("README.md"), "an older vault\n")

        VaultDashboard(root).ensure()

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

        VaultDashboard(root).ensure()

        Files.readString(dashboard) shouldBe "views: [] # mine\n"
    }

    /**
     * The third state (#300). Absent and edited were the only two considered, which left a file
     * **nobody had touched** frozen at whatever shipped the day the vault was made — and twice on
     * 2026-08-13 an improvement reached the one existing vault only by hand.
     */
    @Test
    fun `refreshes a seed the reader never touched`() {
        VaultDashboard(root).ensure()
        Files.writeString(dashboard, "stale content the server itself wrote")
        SeedLedger(root).record("dashboard.base", "stale content the server itself wrote")

        VaultDashboard(root).ensure()

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

        VaultDashboard(root).ensure()

        Files.readString(dashboard) shouldBe "from a vault older than the ledger\n"
    }

    /** Startup runs on every boot; the second pass must be a no-op, not a second write. */
    @Test
    fun `is idempotent across boots`() {
        VaultDashboard(root).ensure()
        val once = Files.readAllBytes(dashboard)

        VaultDashboard(root).ensure()

        Files.readAllBytes(dashboard) shouldBe once
    }

    /**
     * An unwritable record repository is a broken setup and still not a reason to refuse to
     * record: the capture is the thing that cannot be replayed (protocol §11), and a grading lost
     * to a dashboard file would be the wrong trade entirely.
     */
    @Test
    fun `does not throw when the vault cannot be written`() {
        VaultDashboard(root.resolve("no/such/directory")).ensure()
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
        VaultDashboard(root).ensure()

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
        VaultDashboard(root).ensure()
        val ours = Files.readString(dashboard)
        SeedLedger(root).record("dashboard.base", "what an older version of this server shipped")

        VaultDashboard(root).ensure()

        SeedLedger(root).isUnchanged("dashboard.base", dashboard).shouldBeTrue()
        Files.readString(dashboard) shouldBe ours
    }

    /** Adoption is not a licence to overwrite: one character different is still theirs. */
    @Test
    fun `does not adopt a seed that differs by a single character`() {
        VaultDashboard(root).ensure()
        Files.writeString(dashboard, Files.readString(dashboard) + " ")
        SeedLedger(root).record("dashboard.base", "something else entirely")

        VaultDashboard(root).ensure()

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
        VaultDashboard(root).ensure()

        val text = Files.readString(dashboard)
        listOf("Recent", "Not passed yet", "By attempts", "By part", "By language", "Tags")
            .forEach { text shouldContain it }
    }
}
