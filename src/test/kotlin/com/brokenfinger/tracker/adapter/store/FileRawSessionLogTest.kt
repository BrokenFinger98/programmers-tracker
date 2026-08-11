package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.RawSessionId
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FileRawSessionLogTest {
    @TempDir
    lateinit var root: Path

    private val startedAt = Instant.parse("2026-08-05T14:23:01.123Z")

    @Test
    fun `names a session file after the start instant and the lesson`() {
        val log = logAt(startedAt)

        log.start(120804).value shouldBe "20260805T142301123Z-120804.jsonl"
    }

    @Test
    fun `the file name stays legal on Windows`() {
        val name = logAt(startedAt).start(120804).value

        // No colon, no other reserved character — CI runs windows-latest.
        name shouldMatch Regex("[A-Za-z0-9._-]+")
    }

    @Test
    fun `rejects a non-positive lesson id instead of naming a file after it`() {
        shouldThrow<IllegalArgumentException> { logAt(startedAt).start(0) }
    }

    @Test
    fun `append creates the raw directory and the file on the first frame`() {
        val log = logAt(startedAt)
        val session = log.start(120804)

        log.append(session, """{"type":"welcome"}""")

        lines(session) shouldContainExactly listOf("""{"type":"welcome"}""")
    }

    @Test
    fun `appends frames in arrival order, one line each`() {
        val log = logAt(startedAt)
        val session = log.start(120804)

        log.append(session, """{"n":1}""")
        log.append(session, """{"n":2}""")

        lines(session) shouldContainExactly listOf("""{"n":1}""", """{"n":2}""")
    }

    @Test
    fun `stores measured frames byte-for-byte instead of re-serializing them`() {
        val frames = FixtureLoader.rawFrames("algorithm-pass.jsonl")
        val log = logAt(startedAt)
        val session = log.start(120804)

        frames.forEach { log.append(session, it) }

        lines(session) shouldContainExactly frames
    }

    @Test
    fun `a frame that already ends with a newline does not produce a blank line`() {
        val log = logAt(startedAt)
        val session = log.start(120804)

        log.append(session, "{\"n\":1}\r\n")
        log.append(session, "{\"n\":2}\n")

        lines(session) shouldContainExactly listOf("""{"n":1}""", """{"n":2}""")
    }

    @Test
    fun `a second log instance appends to the same file instead of truncating it`() {
        val first = logAt(startedAt)
        val session = first.start(120804)
        first.append(session, """{"n":1}""")

        logAt(startedAt).append(session, """{"n":2}""")

        lines(session) shouldContainExactly listOf("""{"n":1}""", """{"n":2}""")
    }

    @Test
    fun `complete copies the finished session to the attempt path, creating its directory`() {
        val log = logAt(startedAt)
        val session = log.start(120804)
        log.append(session, """{"n":1}""")
        val destination = root.resolve("problems/120804-x/attempts/001.raw.jsonl")

        log.complete(session, destination) shouldBe destination

        Files.readAllLines(destination) shouldContainExactly listOf("""{"n":1}""")
        // The source stays: it is the recovery queue, and it must outlive the record that
        // names the destination (#95). `discard` is what retires it.
        Files.exists(rawDir().resolve(session.value)) shouldBe true
    }

    @Test
    fun `discard retires a session whose record is durable`() {
        val log = logAt(startedAt)
        val session = log.start(120804)
        log.append(session, """{"n":1}""")
        log.complete(session, root.resolve("problems/120804-x/attempts/001.raw.jsonl"))

        log.discard(session)

        Files.exists(rawDir().resolve(session.value)) shouldBe false
    }

    /** Retiring what was never there is not an error — a retry must not fail on it. */
    @Test
    fun `discarding an absent session is silent`() {
        logAt(startedAt).discard(RawSessionId("never-existed.jsonl"))
    }

    @Test
    fun `complete refuses to overwrite an existing destination and keeps the source`() {
        val log = logAt(startedAt)
        val session = log.start(120804)
        log.append(session, """{"n":1}""")
        val destination = Files.writeString(root.resolve("001.raw.jsonl"), "older\n")

        shouldThrow<FileAlreadyExistsException> { log.complete(session, destination) }

        Files.readString(destination) shouldBe "older\n"
        lines(session) shouldContainExactly listOf("""{"n":1}""")
    }

    @Test
    fun `complete on an unknown session fails loudly rather than silently succeeding`() {
        val log = logAt(startedAt)

        shouldThrow<NoSuchFileException> { log.complete(log.start(120804), root.resolve("001.raw.jsonl")) }
    }

    @Test
    fun `unprocessed is empty before any frame has ever been received`() {
        logAt(startedAt).unprocessed() shouldBe emptyList()
    }

    @Test
    fun `unprocessed reports the crash-recovery work list oldest first`() {
        val older = logAt(Instant.parse("2026-08-05T14:23:01.123Z"))
        val newer = logAt(Instant.parse("2026-08-05T15:00:00.000Z"))
        older.append(older.start(120804), """{"n":1}""")
        newer.append(newer.start(131528), """{"n":2}""")

        val pending = older.unprocessed()

        pending.map { it.lessonId } shouldContainExactly listOf(120804L, 131528L)
        pending.first().startedAt shouldBe startedAt
        pending.first().path shouldBe rawDir().resolve("20260805T142301123Z-120804.jsonl")
    }

    @Test
    fun `unprocessed ignores files this log did not write`() {
        val log = logAt(startedAt)
        log.append(log.start(120804), """{"n":1}""")
        Files.writeString(rawDir().resolve(".DS_Store"), "junk")
        Files.createDirectory(rawDir().resolve("nested"))

        log.unprocessed() shouldHaveSize 1
    }

    /**
     * Copying alone must NOT retire it: until the record naming the destination is durable,
     * this session is still the only place the grading can be recovered from (#95).
     */
    @Test
    fun `a copied session stays on the work list until it is discarded`() {
        val log = logAt(startedAt)
        val session = log.start(120804)
        log.append(session, """{"n":1}""")

        log.complete(session, root.resolve("attempts/001.raw.jsonl"))
        log.unprocessed().map { it.id } shouldContainExactly listOf(session)

        log.discard(session)
        log.unprocessed() shouldBe emptyList()
    }

    /**
     * Kept so a person can read them, and never on the work list: without a `start` there is
     * no action and no identity, so replaying them could only fail forever (#107).
     */
    @Test
    fun `orphaned frames are kept per lesson and never join the work list`() {
        val log = logAt(startedAt)

        log.orphaned(120804, """{"n":1}""")
        log.orphaned(120804, """{"n":2}""")
        log.orphaned(181951, """{"n":3}""")

        Files.readAllLines(rawDir().resolve("orphans/120804.jsonl"))
            .shouldContainExactly("""{"n":1}""", """{"n":2}""")
        Files.readAllLines(rawDir().resolve("orphans/181951.jsonl")).shouldContainExactly("""{"n":3}""")
        log.unprocessed().shouldBeEmpty()
    }

    @Test
    fun `under resolves the raw directory inside the record repository`() {
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC))
        val session = log.start(120804)

        log.append(session, """{"n":1}""")

        Files.exists(root.resolve(".ps/raw").resolve(session.value)) shouldBe true
    }

    private fun logAt(instant: Instant) = FileRawSessionLog(rawDir(), Clock.fixed(instant, ZoneOffset.UTC))

    private fun rawDir(): Path = root.resolve("raw")

    private fun lines(session: RawSessionId): List<String> = Files.readAllLines(rawDir().resolve(session.value))

    // Unique names ------------------------------------------------------------------------------

    /**
     * Two gradings can open in the same millisecond — measured 2026-08-11, when two channels
     * for one lesson did exactly that. The name was `<stamp>-<lesson>.jsonl` and nothing else,
     * so both wrote into one file and one replaced the other on retirement: two gradings
     * interleaved in a single capture, and the only copy of each destroyed (#157).
     */
    @Test
    fun `two sessions opened in the same millisecond get different names`() {
        val log = logAt(sameMillisecond)

        val first = log.start(120805)
        val second = log.start(120805)

        first shouldNotBe second
    }

    /**
     * And the second must still be a session. A discriminator the work-list walk cannot parse
     * would take the capture off the reconciler entirely — a quieter loss than the one this
     * fixes.
     */
    @Test
    fun `a session that had to be discriminated is still on the work list`() {
        val log = logAt(sameMillisecond)
        val first = log.start(120805)
        val second = log.start(120805)

        log.append(first, """{"a":1}""")
        log.append(second, """{"b":2}""")

        log.unprocessed().map { it.id } shouldContainExactlyInAnyOrder listOf(first, second)
        log.unprocessed().map { it.lessonId }.toSet() shouldBe setOf(120805L)
    }

    /** A name already on disk from an earlier run is not handed out again either. */
    @Test
    fun `a name already taken on disk is not reissued`() {
        val earlier = logAt(sameMillisecond)
        val taken = earlier.start(120805)
        earlier.append(taken, """{"a":1}""")

        val restarted = logAt(sameMillisecond)

        restarted.start(120805) shouldNotBe taken
    }

    /** One instant, so every session opened with it collides unless the log prevents it. */
    private val sameMillisecond: Instant = Instant.parse("2026-08-11T05:26:42.748Z")
}
