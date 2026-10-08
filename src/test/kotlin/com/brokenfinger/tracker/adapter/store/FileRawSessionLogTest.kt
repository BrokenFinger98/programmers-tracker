package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.RawSessionId
import com.brokenfinger.tracker.support.fixtures.ChangingAnswer
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.NOTHING_TRACKED
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aListingThatFailsOnce
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FileRawSessionLogTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

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
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root))
        val session = log.start(120804)

        log.append(session, """{"n":1}""")

        Files.exists(root.resolve(".ps/raw").resolve(session.value)) shouldBe true
    }

    /**
     * The work list is listed only through real directories (#387). Replaying a session makes a record, and with `raw`
     * a link the sessions it leads to are not the tracker's own: whatever stands there would become one. Nothing is
     * listed while the link stands, that is said, and what lies behind it is left where it is.
     */
    @Test
    fun `a raw directory that is a link lists no work, and says so`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.createDirectories(outside.resolve("raw"))
        FileRawSessionLog(elsewhere, Clock.fixed(startedAt, ZoneOffset.UTC)).let { it.append(it.start(120804), "{}") }
        aLink(root.resolve(".ps/raw"), elsewhere)
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root))

        val heard = warningsWhile(FileRawSessionLog::class) { log.unprocessed().shouldBeEmpty() }

        heard.single() shouldContain "work list was not read"
        heard.single() shouldContain "symbolic link"
        namesIn(elsewhere) shouldHaveSize 1
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

    /**
     * `.ps/raw` a tracked link into the tree, as a pull can deliver it: frames appended through it are
     * paths git tracks, and the server's own reconciliation committed and pushed them. Nothing is
     * written there, and the skip is said once for the log (#360).
     */
    @Test
    fun `no frame is written through a link where the raw directory was`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        aLink(root.resolve(".ps/raw"), tracked)
        val log = FileRawSessionLog.under(root, Clock.fixed(sameMillisecond, ZoneOffset.UTC), aStateDirectory(root))

        val heard = warningsWhile(FileRawSessionLog::class) {
            val session = log.start(120804)
            log.append(session, """{"a":1}""")
            log.append(session, """{"b":2}""")
            log.orphaned(120804, """{"c":3}""")
        }

        Files.list(tracked).use { it.count() } shouldBe 0L
        heard.single() shouldContain "is a symbolic link"
    }

    // While .ps is refused, frames are held, never discarded (#360) ----------------------------

    /**
     * A refusal that can pass — a read that failed — was kept for the whole session, which then lost
     * every frame: 3 of 500 sessions while a timer was written every 2 ms (the review of ea1357c). It is
     * asked again at the next frame, and what was held goes first.
     */
    @Test
    fun `a session refused for a moment keeps every frame, in order`() {
        val state = StateDirectory(root, NOTHING_TRACKED, listing = aListingThatFailsOnce())
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), state)
        val session = log.start(120804)

        log.append(session, """{"n":1}""")
        log.append(session, """{"n":2}""")

        Files.readAllLines(stateRaw(session)) shouldContainExactly listOf("""{"n":1}""", """{"n":2}""")
    }

    /** Skipping a refused session's frames discarded originals; its attempt file lies outside `.ps`, so they go there. */
    @Test
    fun `a submit refused for good still reaches its attempt file whole`() {
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root) { true })
        val session = log.start(120804)
        log.append(session, """{"n":1}""")
        log.append(session, """{"n":2}""")
        val destination = root.resolve("problems/120804-x/attempts/001.raw.jsonl")

        log.complete(session, destination)

        Files.readAllLines(destination) shouldContainExactly listOf("""{"n":1}""", """{"n":2}""")
        Files.exists(stateRaw(session)) shouldBe false
    }

    @Test
    fun `a run set aside while refused is written once the state directory is usable again`() {
        val git = ChangingAnswer(true)
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root, git))
        val run = log.start(120804)
        log.append(run, """{"run":1}""")
        log.setAside(run)
        git.answer = false

        log.append(log.start(120805), """{"next":1}""")

        Files.readAllLines(root.resolve(".ps/raw/recorded/${run.value}")) shouldContainExactly listOf("""{"run":1}""")
    }

    @Test
    fun `orphans kept while refused are written once the state directory is usable again`() {
        val git = ChangingAnswer(true)
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root, git))
        log.orphaned(120804, """{"lost":1}""")
        git.answer = false

        log.orphaned(120804, """{"lost":2}""")

        Files.readAllLines(root.resolve(".ps/raw/orphans/120804.jsonl")) shouldContainExactly
            listOf("""{"lost":1}""", """{"lost":2}""")
    }

    /**
     * The verdict a session's first frame got was kept for every later one, so a raw directory a pull
     * swapped for a link between two frames was written through. The directory is checked before each.
     */
    @Test
    fun `a raw directory swapped for a link mid-session is never written through`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root))
        val session = log.start(120804)
        log.append(session, """{"n":1}""")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        Files.move(root.resolve(".ps/raw"), root.resolve("moved-away"))
        aLink(root.resolve(".ps/raw"), tracked)

        log.append(session, """{"n":2}""")

        Files.list(tracked).use { it.count() } shouldBe 0L
    }

    @Test
    fun `held frames stay within their limit, and going over it is said`() {
        val state = aStateDirectory(root) { true }
        val log =
            FileRawSessionLog(root.resolve(".ps/raw"), Clock.fixed(startedAt, ZoneOffset.UTC), state, heldLimit = 10)
        val session = log.start(120804)
        val destination = root.resolve("001.raw.jsonl")

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.append(session, """{"n":1}""")
            log.append(session, """{"n":2}""")
        }
        log.complete(session, destination)

        Files.readAllLines(destination) shouldContainExactly listOf("""{"n":1}""")
        heard.last() shouldContain "reached 10 characters"
    }

    @Test
    fun `what is still held when the log closes is said`() {
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root) { true })
        log.append(log.start(120804), """{"n":1}""")

        val heard = warningsWhile(FileRawSessionLog::class) { log.close() }

        heard.single() shouldContain "were lost when the server stopped"
    }

    /** Usable again by the time the server stops: a live session's frames go onto the work list, which the next start replays. */
    @Test
    fun `what is held is written when the log closes, once the state directory is usable`() {
        val git = ChangingAnswer(true)
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root, git))
        val session = log.start(120804)
        log.append(session, """{"n":1}""")
        git.answer = false

        log.close()

        Files.readAllLines(stateRaw(session)) shouldContainExactly listOf("""{"n":1}""")
    }

    // The copy beside the record, never through a link (#361) ------------------------------------------

    /**
     * `CREATE_NEW` never wrote through a link standing where the copy goes, but the directories above it were made
     * through one, and the copy landed where it led. It is refused now, and the frames stay on the work list, where
     * the writer sets them aside as before.
     */
    @Test
    fun `a submit's frames are not copied through an attempts directory that is a link`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/120804-x/attempts"), outside)
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root))
        val session = log.start(120804)
        log.append(session, """{"n":1}""")

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> {
                log.complete(session, root.resolve("problems/120804-x/attempts/001.raw.jsonl"))
            }
        }

        namesIn(outside).shouldBeEmpty()
        log.unprocessed().map { it.id } shouldContainExactly listOf(session)
        heard.single() shouldContain "attempts is a symbolic link"
    }

    /** Pinned, not new: anything standing where the copy goes was refused before, a dangling link included. */
    @Test
    fun `a submit's frames are never copied where a dangling link stands, or where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-copy.jsonl")
        val destination = aLink(root.resolve("problems/120804-x/attempts/001.raw.jsonl"), nowhere)
        val log = FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), aStateDirectory(root))
        val session = log.start(120804)
        log.append(session, """{"n":1}""")

        shouldThrow<FileAlreadyExistsException> { log.complete(session, destination) }

        Files.exists(nowhere, LinkOption.NOFOLLOW_LINKS) shouldBe false
    }

    private fun stateRaw(session: RawSessionId): Path = root.resolve(".ps/raw").resolve(session.value)

    /** One instant, so every session opened with it collides unless the log prevents it. */
    private val sameMillisecond: Instant = Instant.parse("2026-08-11T05:26:42.748Z")
}
