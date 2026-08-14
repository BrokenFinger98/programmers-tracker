package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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

    private fun ledger() = SeedLedger(root)
}
