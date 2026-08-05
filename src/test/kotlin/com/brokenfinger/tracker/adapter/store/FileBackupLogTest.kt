package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Layer test for the backup state document (dev rules §6.1) — real files under a [TempDir]. */
class FileBackupLogTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `an instant written is the instant read back`() {
        val log = backupLog()

        log.succeededAt(BACKED_UP_AT)

        backupLog().lastSuccessAt() shouldBe BACKED_UP_AT
    }

    @Test
    fun `a backup that never ran reports nothing rather than a date`() {
        backupLog().lastSuccessAt() shouldBe null
    }

    @Test
    fun `the newest backup replaces the previous one`() {
        val log = backupLog()
        log.succeededAt(BACKED_UP_AT)

        log.succeededAt(BACKED_UP_AT.plusSeconds(86_400))

        log.lastSuccessAt() shouldBe BACKED_UP_AT.plusSeconds(86_400)
    }

    // Failure paths ----------------------------------------------------------------------------

    /**
     * Leniently, in the direction that costs a redundant push rather than a skipped day: an
     * unreadable document must not be why a week of attempts never leaves the machine.
     */
    @Test
    fun `a document nothing can parse reads as never backed up`() {
        write("{ not json")

        backupLog().lastSuccessAt() shouldBe null
    }

    @Test
    fun `a document whose instant is not one reads as never backed up`() {
        write("""{"lastSuccessAt":"yesterday evening"}""")

        backupLog().lastSuccessAt() shouldBe null
    }

    @Test
    fun `a document missing the field reads as never backed up`() {
        write("""{"somethingElse":1}""")

        backupLog().lastSuccessAt() shouldBe null
    }

    private fun backupLog() = FileBackupLog.under(root)

    private fun write(text: String) {
        val file = root.resolve(".ps/backup.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private companion object {
        val BACKED_UP_AT: Instant = Instant.parse("2026-08-05T14:02:00Z")
    }
}
