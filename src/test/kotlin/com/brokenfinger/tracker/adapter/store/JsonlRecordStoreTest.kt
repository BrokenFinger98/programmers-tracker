package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.AttemptAuthority
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.aRecordLine
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class JsonlRecordStoreTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `reading a log that does not exist yet is empty, not an error`() {
        store().read() shouldBe emptyList()
    }

    @Test
    fun `append creates the log directory and the file on the first record`() {
        store().append(aRecordLine())

        lines() shouldContainExactly listOf(aRecordLine())
    }

    @Test
    fun `records are appended in order, one line each`() {
        val store = store()

        store.append(aRecordLine(attempt = 1))
        store.append(aRecordLine(attempt = 2))

        store.read().map { it.attempt } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a second store instance appends instead of truncating`() {
        store().append(aRecordLine(attempt = 1))

        store().append(aRecordLine(attempt = 2))

        store().read().map { it.attempt } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a torn final line is skipped and every earlier record survives`() {
        writeLog(aRecordLine(attempt = 1) + "\n" + aRecordLine(attempt = 2) + "\n" + """{"lessonId":120804,"att""")

        store().read().map { it.attempt } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a malformed line in the middle costs only that line`() {
        writeLog(aRecordLine(attempt = 1) + "\nnot json at all\n" + aRecordLine(attempt = 3) + "\n")

        store().read().map { it.attempt } shouldContainExactly listOf(1, 3)
    }

    @Test
    fun `blank lines are skipped without complaint`() {
        writeLog("\n" + aRecordLine(attempt = 1) + "\n\n" + aRecordLine(attempt = 2) + "\n")

        store().read().map { it.attempt } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `appending after a crash-torn line does not glue the new record onto it`() {
        writeLog(aRecordLine(attempt = 1) + "\n" + """{"lessonId":120804,"att""")

        store().append(aRecordLine(attempt = 2))

        // The torn line is lost — it was never complete — but the new record is a line of
        // its own, so exactly one record is missing rather than two.
        store().read().map { it.attempt } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `a record already ending in a newline does not produce a blank line`() {
        store().append(aRecordLine() + "\n")

        lines() shouldContainExactly listOf(aRecordLine())
    }

    @Test
    fun `refuses a record carrying an interior newline — it would corrupt two lines at once`() {
        shouldThrow<IllegalArgumentException> { store().append("""{"lessonId":1,""" + "\n" + """"attempt":1}""") }
    }

    @Test
    fun `refuses a blank record`() {
        shouldThrow<IllegalArgumentException> { store().append("  ") }
    }

    @Test
    fun `a log left by a crash still restores the attempt authority`() {
        writeLog(aRecordLine(attempt = 1) + "\n" + aRecordLine(attempt = 2) + "\n" + """{"lessonId":120804,"att""")

        val authority = AttemptAuthority.from(store().read())

        authority.allocate(120804, GradingAction.SUBMIT) shouldBe 3
    }

    @Test
    fun `under resolves the submission log inside the record repository`() {
        JsonlRecordStore.under(root).append(aRecordLine())

        Files.exists(root.resolve("log/submissions.jsonl")) shouldBe true
    }

    // Appended to, never through a link (#361) ------------------------------------------------------

    /**
     * The attempt authority, and a pull can deliver it as a link. A record is never appended where a link leads:
     * the append is refused and thrown, as any failed append is, and the writer leaves the grading's frames on the
     * work list for a boot after the link is gone to replay.
     */
    @Test
    fun `a submission log that is a link is refused, and the file it leads to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        aLink(logFile(), token)

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { store().append(aRecordLine()) }
        }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        heard.single() shouldContain logFile().toString()
        heard.single() shouldContain "log/submissions.jsonl is a symbolic link"
    }

    @Test
    fun `a log directory that is a link is refused, and nothing is written where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("log"), outside)

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { store().append(aRecordLine()) }
        }

        namesIn(outside).shouldBeEmpty()
        heard.single() shouldContain "log is a symbolic link"
    }

    @Test
    fun `a submission log that is a dangling link is refused, and nothing is created where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-record.jsonl")
        aLink(logFile(), nowhere)

        shouldThrow<RefusedWriteException> { store().append(aRecordLine()) }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
    }

    // Read through no link either, and never answered as empty (#387) ---------------------------------

    /**
     * The read agrees with the append. A pull can deliver the log as a link, and the append refuses it (#361); read on
     * through it, the history was whatever the link led to — here another log's records — while nothing more was
     * recorded. It is refused instead, and thrown: an empty answer would be every reader's "no submissions".
     */
    @Test
    fun `a submission log that is a link is refused rather than read, and is never an empty history`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(logFile(), anotherLog())
        Files.readString(logFile()) shouldContain "120804"

        val heard = warningsWhile(RecordReads::class) { shouldThrow<RefusedReadException> { store().read() } }

        heard.single() shouldContain logFile().toString()
        heard.single() shouldContain "log/submissions.jsonl is a symbolic link"
        heard.single() shouldNotContain outside.toString()
    }

    @Test
    fun `a log directory that is a link is refused rather than read`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        anotherLog()
        aLink(root.resolve("log"), outside)

        val refusal = shouldThrow<RefusedReadException> { store().read() }

        refusal.message shouldContain "log is a symbolic link"
    }

    /** A dangling link was no regular file, and so read as a log not written yet: nothing recorded at all. */
    @Test
    fun `a submission log that is a dangling link is refused, not read as a log not written yet`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(logFile(), outside.resolve("gone.jsonl"))

        shouldThrow<RefusedReadException> { store().read() }
    }

    /** `log/` that cannot be searched said nothing about the log, and was read as no log at all. */
    @Test
    fun `a log that cannot be looked at is an error, not a log not written yet`() {
        assumeTrue(keepsPosixPermissions(root), "this test changes POSIX permissions")
        writeLog(aRecordLine() + "\n")
        Files.setPosixFilePermissions(logFile().parent, PosixFilePermissions.fromString("rw-------"))
        try {
            assumeTrue(!Files.isReadable(logFile()), "a superuser searches anyway")
            shouldThrow<AccessDeniedException> { store().read() }
        } finally {
            Files.setPosixFilePermissions(logFile().parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // A log outside the records repository, holding one real-looking record.
    private fun anotherLog(): Path = Files.writeString(outside.resolve("submissions.jsonl"), aRecordLine() + "\n")

    private fun store() = JsonlRecordStore(logFile())

    private fun logFile(): Path = root.resolve("log/submissions.jsonl")

    private fun writeLog(content: String) {
        Files.createDirectories(logFile().parent)
        Files.writeString(logFile(), content)
    }

    private fun lines(): List<String> = Files.readAllLines(logFile())
}
