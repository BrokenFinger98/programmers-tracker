package com.brokenfinger.tracker.adapter.store

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.KeptCode
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSubmit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Layer test over a real directory (dev rules §6.1): what [RunLog] and the attempt writer leave on
 * disk is read back by the read side, which holds no writer of its own.
 */
class FileGradingCodesTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `reads each run's code back by its record id, with the time it was attached`() {
        val run = aRun(at = "2026-10-07T10:51:49+09:00")
        writeRun(run, "select 1", attachedAt = "2026-10-07T10:51:49.38+09:00")

        codes().runs(run.lessonId, run.title)[run.recordId()] shouldBe
            KeptCode("select 1", OffsetDateTime.parse("2026-10-07T10:51:49.38+09:00"))
    }

    @Test
    fun `a problem with no run log keeps nothing`() {
        codes().runs(120804, "두 수의 곱 구하기").shouldBeEmpty()
    }

    /** The crash the writer heals must not cost the reader the lines before it. */
    @Test
    fun `a torn last line is left out and the lines before it survive`() {
        val run = aRun(at = "2026-10-07T10:51:49+09:00")
        writeRun(run, "select 1", attachedAt = "2026-10-07T10:51:49.38+09:00")
        Files.writeString(runLog(), "{\"recordId\":\"torn", StandardOpenOption.APPEND)

        codes().runs(run.lessonId, run.title).keys shouldBe setOf(run.recordId())
    }

    /** What the next append leaves behind: the writer heals a torn tail by starting a fresh line. */
    @Test
    fun `a torn line between two good ones costs neither`() {
        val before = aRun(at = "2026-10-07T10:51:49+09:00")
        val after = aRun(at = "2026-10-07T10:52:30+09:00")
        writeRun(before, "select 1", attachedAt = "2026-10-07T10:51:49.38+09:00")
        Files.writeString(runLog(), "{\"recordId\":\"torn", StandardOpenOption.APPEND)
        writeRun(after, "select 2", attachedAt = "2026-10-07T10:52:30.31+09:00")

        codes().runs(before.lessonId, before.title).mapValues { it.value.text } shouldBe
            mapOf(before.recordId() to "select 1", after.recordId() to "select 2")
    }

    @Test
    fun `an unreadable attach time leaves the code with no time`() {
        writeLines(lineOf("r#1", fetchedAt = "later", code = "x"))

        codes().runs(120804, "두 수의 곱 구하기")["r#1"] shouldBe KeptCode("x", null)
    }

    /** The writer treats the first complete line as the record, and the reader agrees. */
    @Test
    fun `a record id that appears twice keeps its first line`() {
        writeLines(
            lineOf("r#1", fetchedAt = "2026-10-07T10:00:00+09:00", code = "first"),
            lineOf("r#1", fetchedAt = "2026-10-07T10:00:09+09:00", code = "second"),
        )

        codes().runs(120804, "두 수의 곱 구하기")["r#1"]?.text shouldBe "first"
    }

    /** A line written by a later version may carry more keys; the code on it is still the code. */
    @Test
    fun `a key this version does not know is ignored`() {
        writeLines(lineOf("r#1", fetchedAt = "2026-10-07T10:00:00+09:00", code = "x", extra = ""","exit":0"""))

        codes().runs(120804, "두 수의 곱 구하기")["r#1"]?.text shouldBe "x"
    }

    @Test
    fun `blank lines are skipped without a word`() {
        writeLines(
            lineOf("r#1", fetchedAt = "2026-10-07T10:00:00+09:00", code = "a"),
            "",
            "   ",
            lineOf("r#2", fetchedAt = "2026-10-07T10:00:09+09:00", code = "b"),
        )

        val warnings = warningsWhile {
            codes().runs(120804, "두 수의 곱 구하기").keys shouldBe setOf("r#1", "r#2")
        }

        warnings.shouldBeEmpty()
    }

    /**
     * Audible, and still not a record: one warning per file with its path and how many lines were left
     * out, never a line — a line holds code, and records stay out of logs (dev rules §7).
     */
    @Test
    fun `lines that cannot be read are counted in one warning that names the file and quotes none of them`() {
        writeLines(
            lineOf("r#1", fetchedAt = "2026-10-07T10:00:00+09:00", code = "a"),
            """{"recordId":"r#2","language":"java","code":"select secret_marker""",
            """{"recordId":"r#3","language":"java"}""",
        )

        val warnings = warningsWhile {
            codes().runs(120804, "두 수의 곱 구하기").keys shouldBe setOf("r#1")
        }

        val warning = warnings.single()
        warning shouldContain "Left 2 unreadable lines"
        warning shouldContain runLog().toString()
        warning shouldNotContain "secret_marker"
    }

    @Test
    fun `reads a submit's code from the path its record carries`() {
        val path = aSubmit(at = "2026-10-07T11:00:00+09:00").codePath!!
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, "select 2\n")

        codes().submitted(path) shouldBe "select 2\n"
    }

    @Test
    fun `a submit whose file is gone has no code`() {
        codes().submitted("problems/120804-x/attempts/009.java").shouldBeNull()
    }

    /** D15. The file exists, so the answer is null because the path is refused, not because it is absent. */
    @Test
    fun `a path that leaves the repository is not followed, even to a file that exists`() {
        val secret = Files.writeString(outside.resolve("secret.txt"), "not ours")
        val climbing = root.relativize(secret).toString()

        Files.exists(root.resolve(climbing)) shouldBe true
        codes().submitted(climbing).shouldBeNull()
    }

    /** The repository root holds the push token beside the records; no line of the log may lead to it. */
    @Test
    fun `a record's path cannot reach a credential that sits beside the problems`() {
        val token = root.resolve(".ps/git-credentials")
        Files.createDirectories(token.parent)
        Files.writeString(token, "not a real credential")

        codes().submitted(".ps/git-credentials").shouldBeNull()
        codes().submitted("problems/../.ps/git-credentials").shouldBeNull()
    }

    @Test
    fun `a directory where a file should be is no code, not an error`() {
        Files.createDirectories(root.resolve("problems/1/attempts/001.java"))

        codes().submitted("problems/1/attempts/001.java").shouldBeNull()
    }

    // Links. git stores them, so one under problems/ can arrive with a clone or a pull, not only by
    // hand — and it passes the lexical bound, because the path it is reached by is `problems/...`.

    @Test
    fun `a link to a credential beside the problems is not followed`() {
        assumeTrue(posix(), "this test makes symbolic links")
        val token = root.resolve(".ps/git-credentials")
        Files.createDirectories(token.parent)
        Files.writeString(token, "not a real credential")
        val attempts = Files.createDirectories(root.resolve("problems/1-x/attempts"))
        Files.createSymbolicLink(attempts.resolve("001.java"), attempts.relativize(token))

        Files.readString(attempts.resolve("001.java")) shouldBe "not a real credential"
        codes().submitted("problems/1-x/attempts/001.java").shouldBeNull()
    }

    @Test
    fun `a problem directory that is a link out of the problems directory is not followed`() {
        assumeTrue(posix(), "this test makes symbolic links")
        Files.writeString(Files.createDirectories(outside.resolve("attempts")).resolve("001.java"), "not ours")
        Files.createSymbolicLink(Files.createDirectories(root.resolve("problems")).resolve("1-x"), outside)

        Files.readString(root.resolve("problems/1-x/attempts/001.java")) shouldBe "not ours"
        codes().submitted("problems/1-x/attempts/001.java").shouldBeNull()
    }

    /**
     * The bound must not move with a link at `problems` itself. Resolving that directory too carries the
     * bound to wherever the link leads, and a clone can bring that link as easily as one below it.
     */
    @Test
    fun `a problems directory that is itself a link to the state directory is not followed`() {
        assumeTrue(posix(), "this test makes symbolic links")
        val state = Files.createDirectories(root.resolve(".ps"))
        Files.writeString(state.resolve("git-credentials"), "not a real credential")
        Files.createSymbolicLink(root.resolve("problems"), Path.of(".ps"))

        Files.readString(root.resolve("problems/git-credentials")) shouldBe "not a real credential"
        codes().submitted("problems/git-credentials").shouldBeNull()
    }

    @Test
    fun `a problems directory that is itself a link out of the repository is not followed`() {
        assumeTrue(posix(), "this test makes symbolic links")
        Files.writeString(Files.createDirectories(outside.resolve("1-x/attempts")).resolve("001.java"), "not ours")
        Files.createSymbolicLink(root.resolve("problems"), outside)

        Files.readString(root.resolve("problems/1-x/attempts/001.java")) shouldBe "not ours"
        codes().submitted("problems/1-x/attempts/001.java").shouldBeNull()
    }

    /** What is resolved is the repository root: a records directory that is itself a link (~/ps-records) still reads. */
    @Test
    fun `a repository root reached through a link still reads its code`() {
        assumeTrue(posix(), "this test makes symbolic links")
        val attempts = Files.createDirectories(root.resolve("problems/1-x/attempts"))
        Files.writeString(attempts.resolve("001.java"), "select 1\n")
        val alias = Files.createSymbolicLink(outside.resolve("records"), root)

        FileGradingCodes(RecordLayout(alias)).submitted("problems/1-x/attempts/001.java") shouldBe "select 1\n"
    }

    /** What matters is where a link leads, not that it is one: a link that stays under problems/ reads as its target. */
    @Test
    fun `a link that stays inside the problems directory is followed`() {
        assumeTrue(posix(), "this test makes symbolic links")
        val attempts = Files.createDirectories(root.resolve("problems/1-x/attempts"))
        Files.writeString(attempts.resolve("001.java"), "select 1\n")
        Files.createSymbolicLink(attempts.resolve("002.java"), Path.of("001.java"))

        codes().submitted("problems/1-x/attempts/002.java") shouldBe "select 1\n"
    }

    @Test
    fun `a link that leads nowhere is no code, not an error`() {
        assumeTrue(posix(), "this test makes symbolic links")
        val attempts = Files.createDirectories(root.resolve("problems/1-x/attempts"))
        Files.createSymbolicLink(attempts.resolve("001.java"), Path.of("gone.java"))

        codes().submitted("problems/1-x/attempts/001.java").shouldBeNull()
    }

    /**
     * Only a regular file is read. A FIFO behind a record's path would block the request thread for a
     * writer that never comes, so what this pins is that the call returns at all. It runs in a thread
     * of its own because the `open` that blocks cannot be interrupted, and the timeout is what fails it
     * if the check is ever removed.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO behind a record's path is no code, and is not waited on`() {
        assumeTrue(posix(), "this test makes a FIFO")
        val fifo = root.resolve("problems/1-x/attempts/001.java")
        Files.createDirectories(fifo.parent)
        assumeTrue(madeFifo(fifo), "no mkfifo on this machine")

        codes().submitted("problems/1-x/attempts/001.java").shouldBeNull()
    }

    private fun writeRun(run: SubmissionRecord, code: String, attachedAt: String) {
        val at = OffsetDateTime.parse(attachedAt)
        RunLog(RecordLayout(root), Clock.fixed(at.toInstant(), at.offset)).append(run, code)
    }

    private fun writeLines(vararg lines: String) {
        Files.createDirectories(runLog().parent)
        Files.writeString(runLog(), lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun lineOf(recordId: String, fetchedAt: String, code: String, extra: String = ""): String =
        """{"recordId":"$recordId","language":"java","codeFetchedAt":"$fetchedAt","code":"$code"$extra}"""

    private fun runLog(): Path = RecordLayout(root).runLog(120804, "두 수의 곱 구하기")

    private fun posix(): Boolean = root.fileSystem.supportedFileAttributeViews().contains("posix")

    private fun madeFifo(path: Path): Boolean =
        runCatching { ProcessBuilder("mkfifo", path.toString()).start().waitFor() == 0 }.getOrDefault(false)

    /** What the reader said at WARN while [action] ran. Logback is what the application logs through. */
    private fun warningsWhile(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(FileGradingCodes::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        runCatching(action).also { logger.detachAppender(appender) }.getOrThrow()
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private fun codes() = FileGradingCodes(RecordLayout(root))
}
