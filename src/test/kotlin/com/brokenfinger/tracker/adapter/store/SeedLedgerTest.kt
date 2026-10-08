package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The test that tells **untouched** from **edited** (#300).
 *
 * Everything here is about the direction the mistake runs. Overwriting something a person wrote
 * cannot be undone from this side; leaving a stale file costs a hand-copy that was the status quo
 * anyway. So every ambiguous case has to answer "edited".
 */
class SeedLedgerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a file still exactly as we wrote it is ours to update`() {
        write("README.md", "the shipped page")
        ledger().record("README.md", "the shipped page")

        ledger().isUnchanged("README.md", root.resolve("README.md")).shouldBeTrue()
    }

    /** One character. The rule #254 states is that editing is respected forever. */
    @Test
    fun `a file changed by a single character is theirs`() {
        write("README.md", "the shipped page.")
        ledger().record("README.md", "the shipped page")

        ledger().isUnchanged("README.md", root.resolve("README.md")).shouldBeFalse()
    }

    /**
     * A vault seeded before this ledger existed. Absence is not permission — the file may well be
     * untouched, and there is no way to know, so it is left alone.
     */
    @Test
    fun `a file we have no record of is treated as edited`() {
        write("README.md", "whatever was there")

        ledger().isUnchanged("README.md", root.resolve("README.md")).shouldBeFalse()
    }

    @Test
    fun `a file that is not there is not unchanged either`() {
        ledger().record("README.md", "the shipped page")

        ledger().isUnchanged("README.md", root.resolve("README.md")).shouldBeFalse()
    }

    /** A corrupt ledger must never become a reason to overwrite somebody's file. */
    @Test
    fun `an unreadable ledger answers edited for everything`() {
        write("README.md", "the shipped page")
        ledger().record("README.md", "the shipped page")
        Files.writeString(root.resolve(".ps/seeds.json"), "{ this is not json")

        ledger().isUnchanged("README.md", root.resolve("README.md")).shouldBeFalse()
    }

    @Test
    fun `recording one seed does not forget the others`() {
        write("README.md", "page")
        write("dashboard.base", "query")
        ledger().record("README.md", "page")
        ledger().record("dashboard.base", "query")

        ledger().isUnchanged("README.md", root.resolve("README.md")).shouldBeTrue()
        ledger().isUnchanged("dashboard.base", root.resolve("dashboard.base")).shouldBeTrue()
    }

    /** Beside the timers and the backup marker — process state, which the records gitignore (#126). */
    @Test
    fun `the ledger lives under the state directory`() {
        ledger().record("README.md", "page")

        Files.exists(root.resolve(".ps/seeds.json")).shouldBeTrue()
    }

    private fun write(name: String, content: String) {
        Files.createDirectories(root)
        Files.writeString(root.resolve(name), content)
    }

    private fun ledger() = SeedLedger(root, aStateDirectory(root))

    /**
     * `.ps/seeds.json` a link to a file outside the repository: the ledger wrote through it, and overwrote
     * that file at every boot (#360, N7). Read, it is no ledger; written, it is replaced, and what it led
     * to is left as it was. A pull delivers such a link tracked, which refuses every writer outright.
     */
    @Test
    fun `the ledger never writes through a link`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val outside = Files.writeString(
            Files.createDirectories(root.resolve("elsewhere")).resolve("owners-file"),
            "theirs\n",
        )
        val records = Files.createDirectories(root.resolve("records"))
        val ledgerFile = aLink(records.resolve(".ps/seeds.json"), outside)

        SeedLedger(records, aStateDirectory(records)).record("dashboard.base", "seeded")

        Files.readString(outside) shouldBe "theirs\n"
        Files.isSymbolicLink(ledgerFile) shouldBe false
        Files.readString(ledgerFile) shouldContain "dashboard.base"
    }
}
