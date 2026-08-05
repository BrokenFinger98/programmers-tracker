package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.AttemptAuthority
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.support.fixtures.aRecordLine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JsonlRecordStoreTest {
    @TempDir
    lateinit var root: Path

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

    private fun store() = JsonlRecordStore(logFile())

    private fun logFile(): Path = root.resolve("log/submissions.jsonl")

    private fun writeLog(content: String) {
        Files.createDirectories(logFile().parent)
        Files.writeString(logFile(), content)
    }

    private fun lines(): List<String> = Files.readAllLines(logFile())
}
