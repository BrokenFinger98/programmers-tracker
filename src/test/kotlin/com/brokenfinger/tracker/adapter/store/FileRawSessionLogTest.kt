package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.adapter.git.TrackedStateEntries
import com.brokenfinger.tracker.application.LeftUnreplayed
import com.brokenfinger.tracker.application.RawSessionId
import com.brokenfinger.tracker.support.fixtures.ChangingAnswer
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.GIT_COULD_NOT_SAY
import com.brokenfinger.tracker.support.fixtures.NOTHING_TRACKED
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aListingThatFailsOnce
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.git.GitWorkspace
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
import io.kotest.matchers.string.shouldNotContain
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

    // A boot replays only what a write would accept (#377) ---------------------------------------

    /**
     * A pull can deliver a file under `.ps/raw` whose name the work list parses, and the next boot
     * replayed it as a grading of the owner's. Not while git tracks anything there: the judgement a write
     * takes is the one a replay takes. The file is left where it is, since it may be the owner's own.
     */
    @Test
    fun `a session under a state directory git tracks anything in is left in place, not replayed`() {
        val session = aSessionLeftBehind()

        logGuardedBy(aStateDirectory(root) { true }).unprocessed().shouldBeEmpty()

        Files.readAllLines(session) shouldContainExactly listOf("""{"n":1}""")
    }

    /** As a pull leaves it: committed elsewhere, checked out by git here. Git's own answer is the oracle. */
    @Test
    fun `a raw session git checked out is not replayed`(@TempDir base: Path) {
        val repo = GitWorkspace(base)
        repo.write(".gitignore", ".ps/\n")
        val pulled = repo.write(".ps/raw/$A_SESSION", """{"n":1}""" + "\n")
        repo.git("add", ".gitignore")
        repo.git("add", "--force", ".ps/raw/$A_SESSION")
        repo.git("commit", "--message", "as a pull delivers it")
        Files.delete(pulled)
        repo.git("checkout", "--", ".ps/raw/$A_SESSION")
        val state = StateDirectory(repo.root, TrackedStateEntries(repo.root))

        FileRawSessionLog.under(repo.root, Clock.fixed(startedAt, ZoneOffset.UTC), state).unprocessed().shouldBeEmpty()

        Files.exists(pulled) shouldBe true
    }

    @Test
    fun `a session behind a raw directory that is a link is not replayed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.createDirectories(root.resolve("problems/zz"))
        Files.writeString(elsewhere.resolve(A_SESSION), """{"n":1}""" + "\n")
        aLink(root.resolve(".ps/raw"), elsewhere)

        logGuardedBy(aStateDirectory(root)).unprocessed().shouldBeEmpty()

        namesIn(elsewhere) shouldContainExactly listOf(A_SESSION)
    }

    @Test
    fun `a session behind a state directory that is a link is not replayed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.createDirectories(root.resolve("problems/zz"))
        Files.createDirectories(elsewhere.resolve("raw"))
        Files.writeString(elsewhere.resolve("raw/$A_SESSION"), """{"n":1}""" + "\n")
        aLink(root.resolve(".ps"), elsewhere)

        logGuardedBy(aStateDirectory(root)).unprocessed().shouldBeEmpty()

        namesIn(elsewhere.resolve("raw")) shouldContainExactly listOf(A_SESSION)
    }

    /** Said once, with how many and why — never a path below `.ps`, a session's name or a frame. */
    @Test
    fun `sessions left in place are said once, with how many and why`() {
        aSessionLeftBehind(120804)
        aSessionLeftBehind(131528)
        val log = logGuardedBy(aStateDirectory(root) { true })

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.unprocessed()
            log.unprocessed()
        }

        heard.single() shouldContain "2 raw session(s) were left in place"
        heard.single() shouldContain StateDirectory.Refusal.TRACKED.reason
        heard.single() shouldNotContain ".ps/"
        heard.single() shouldNotContain "120804"
    }

    /**
     * The link check ran before git was asked, so a raw directory swapped for a link during the git call was
     * listed through it (the review of PR #395, measured). It runs after git answers, just before the listing.
     */
    @Test
    fun `a raw directory swapped for a link while git answers is not listed through`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aSessionLeftBehind()
        val elsewhere = Files.createDirectories(root.resolve("problems/zz"))
        Files.writeString(elsewhere.resolve(A_SESSION), """{"n":1}""" + "\n")
        val swapping = ChangingAnswer(false) { swapForALink(root.resolve(".ps/raw"), elsewhere) }

        logGuardedBy(aStateDirectory(root, swapping)).unprocessed().shouldBeEmpty()
    }

    /**
     * A session file that is itself a link was listed, and read through it: linked to frames in the tree, it
     * was recorded (the review of PR #395, measured). The tracker writes no link there, so only regular files
     * are listed, and the rest are said once, by how many.
     */
    @Test
    fun `a session file that is a link is not listed, and that is said once`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val frames = Files.createDirectories(root.resolve("problems/zz")).resolve("frames.jsonl")
        aLink(root.resolve(".ps/raw/$A_SESSION"), Files.writeString(frames, """{"n":1}""" + "\n"))
        val log = logGuardedBy(aStateDirectory(root))

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.unprocessed().shouldBeEmpty()
            log.unprocessed().shouldBeEmpty()
        }

        heard.single() shouldContain "1 raw session(s) were not replayed because each is not a regular file"
        heard.single() shouldNotContain ".ps/"
    }

    /**
     * Counting them would list a directory through a link, so they are said to be left, not counted. The words
     * name no link: the same message serves a directory that could not be inspected (the review of PR #395).
     */
    @Test
    fun `sessions behind a link are said to be left, and are not counted through it`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.createDirectories(root.resolve("problems/zz"))
        Files.writeString(elsewhere.resolve(A_SESSION), """{"n":1}""" + "\n")
        aLink(root.resolve(".ps/raw"), elsewhere)

        val heard = warningsWhile(FileRawSessionLog::class) { logGuardedBy(aStateDirectory(root)).unprocessed() }

        heard.single() shouldContain "Their directory was not listed, so nothing in it was read or counted"
        heard.single() shouldContain StateDirectory.Refusal.HOLDS_A_LINK.reason
    }

    @Test
    fun `a refused state directory with no session in it says nothing`() {
        Files.createDirectories(root.resolve(".ps/raw"))

        val heard = warningsWhile(FileRawSessionLog::class) {
            logGuardedBy(aStateDirectory(root) { true }).unprocessed().shouldBeEmpty()
        }

        heard.shouldBeEmpty()
    }

    @Test
    fun `a session left in place is replayed once the state directory is usable again`() {
        val git = ChangingAnswer(true)
        aSessionLeftBehind()
        val log = logGuardedBy(aStateDirectory(root, git))
        log.unprocessed().shouldBeEmpty()
        git.answer = false

        log.unprocessed().map { it.lessonId } shouldContainExactly listOf(120804L)
    }

    /** The same judgement a write takes: git that cannot say what it tracks now stops neither a capture nor its replay. */
    @Test
    fun `git that cannot say what it tracks does not stop a replay`() {
        aSessionLeftBehind()

        logGuardedBy(aStateDirectory(root, ChangingAnswer(null))).unprocessed() shouldHaveSize 1
    }

    // A session git has ever tracked is never replayed (#377, the review of PR #395) --------------

    /**
     * Upstream committed a forged session under `.ps/raw`, the owner pulled, followed the TRACKED advice to
     * untrack it and restarted, and the forged PASS was recorded (the review of PR #395, measured end to end).
     * Untracked, it reads like the tracker's own; git's history still names it, and that decides.
     */
    @Test
    fun `a session git checked out and the owner then untracked is not replayed`(@TempDir base: Path) {
        val repo = GitWorkspace(base)
        repo.write(".gitignore", ".ps/\n")
        val pulled = repo.write(".ps/raw/$A_SESSION", """{"n":1}""" + "\n")
        repo.git("add", ".gitignore")
        repo.git("add", "--force", ".ps/raw/$A_SESSION")
        repo.git("commit", "--message", "as a pull delivers it")
        repo.git("rm", "-r", "--cached", "--quiet", ".ps")
        repo.git("commit", "--message", "the advice followed")
        val state = StateDirectory(repo.root, TrackedStateEntries(repo.root))

        FileRawSessionLog.under(repo.root, Clock.fixed(startedAt, ZoneOffset.UTC), state).unprocessed().shouldBeEmpty()

        Files.exists(pulled) shouldBe true
    }

    @Test
    fun `a session git has ever tracked is left in place, never replayed, and said once`() {
        val session = aSessionLeftBehind()
        val log = logGuardedBy(aStateDirectory(root, ChangingAnswer(false, history = setOf("raw/$A_SESSION"))))

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.unprocessed().shouldBeEmpty()
            log.unprocessed().shouldBeEmpty()
        }

        Files.exists(session) shouldBe true
        heard.single() shouldContain "1 raw session(s) were left in place and will never be replayed"
        heard.single() shouldContain "not counted as gaps in the history"
        heard.single() shouldNotContain ".ps/"
    }

    /** Git keeps a name as it was committed, and a filesystem that folds case answers it for ours. */
    @Test
    fun `a session git has tracked in another case is not replayed either`() {
        aSessionLeftBehind()
        val history = setOf("RAW/" + A_SESSION.lowercase())

        logGuardedBy(aStateDirectory(root, ChangingAnswer(false, history = history))).unprocessed().shouldBeEmpty()
    }

    /**
     * One question to git a start, whatever the work list holds, and only the sessions it names stay. A path
     * elsewhere below `.ps` that ends in a session's name is not that session.
     */
    @Test
    fun `a session git has never tracked is replayed beside one it has`() {
        aSessionLeftBehind(120804)
        aSessionLeftBehind(131528)
        val elsewhere = listOf("git-credentials", "raw/recorded/x.jsonl", "recorded/20260805T142301123Z-131528.jsonl")
        val git = ChangingAnswer(false, history = setOf("raw/$A_SESSION") + elsewhere)

        val replayed = logGuardedBy(aStateDirectory(root, git)).unprocessed()

        replayed.map { it.lessonId } shouldContainExactly listOf(131528L)
        git.historyAsked shouldBe 1
    }

    /** Unknown is not "never": while git cannot say what it has ever tracked, nothing is replayed, and that is said. */
    @Test
    fun `nothing is replayed while git cannot say what it has ever tracked`() {
        aSessionLeftBehind()
        val log = logGuardedBy(aStateDirectory(root, ChangingAnswer(false, history = null)))

        val heard = warningsWhile(FileRawSessionLog::class) {
            log.unprocessed().shouldBeEmpty()
            log.unprocessed().shouldBeEmpty()
        }

        heard.single() shouldContain "1 raw session(s) were left in place, not replayed: git could not say"
        heard.single() shouldContain GIT_COULD_NOT_SAY
    }

    /**
     * Skipping the delete, the owner untracked a session a pull delivered, reset past its commit and force-pushed:
     * only the reflog still named it, and it was replayed (the review of PR #395, measured on real git).
     */
    @Test
    fun `a session git delivered is not replayed once a reset and a force-push leave it to the reflog`(
        @TempDir base: Path,
    ) {
        val repo = GitWorkspace(base)
        repo.withRemote()
        val before = repo.git("rev-parse", "HEAD").trim()
        repo.write(".ps/raw/$A_SESSION", """{"n":1}""" + "\n")
        repo.git("add", "--force", ".ps/raw/$A_SESSION")
        repo.git("commit", "--message", "as a pull delivers it")
        repo.git("rm", "-r", "--cached", "--quiet", ".ps")
        repo.git("commit", "--message", "untracked, the file left on disk")
        repo.git("reset", "--quiet", "--hard", before)
        repo.git("push", "--quiet", "--force", "origin", "main")
        val state = StateDirectory(repo.root, TrackedStateEntries(repo.root))

        FileRawSessionLog.under(repo.root, Clock.fixed(startedAt, ZoneOffset.UTC), state).unprocessed().shouldBeEmpty()
    }

    // What a start leaves unreplayed is kept for the history's readers (#377, #169) ---------------

    /**
     * Left in place at a start, a session is a grading no record represents until a later start, and MCP said
     * nothing of it: the WARN reached the log alone (the review of PR #395). The log keeps the count for readers.
     */
    @Test
    fun `sessions a start leaves in place are counted for the history's readers`() {
        aSessionLeftBehind(120804)
        aSessionLeftBehind(131528)
        val log = logGuardedBy(aStateDirectory(root) { true })

        log.unprocessed()

        log.unreplayed() shouldBe LeftUnreplayed(2, uncounted = false)
    }

    /**
     * A session git has known stays on disk, never replayed, until someone deletes it. Counted, it put a mark on
     * every MCP answer for good after one pull (the review of PR #395). Git delivered it, so it is no gap in what
     * this server captured: it is said in the log at each start and not counted. Sessions git could not vouch for
     * are counted, since a later start replays them.
     */
    @Test
    fun `sessions git could not vouch for are counted as left, and those it has known are not`() {
        aSessionLeftBehind(120804)
        aSessionLeftBehind(131528)
        val known = logGuardedBy(aStateDirectory(root, ChangingAnswer(false, history = setOf("raw/$A_SESSION"))))
        val unanswered = logGuardedBy(aStateDirectory(root, ChangingAnswer(false, history = null)))

        known.unprocessed()
        unanswered.unprocessed()

        known.unreplayed() shouldBe LeftUnreplayed.NOTHING
        unanswered.unreplayed() shouldBe LeftUnreplayed(2, uncounted = false)
    }

    @Test
    fun `a session file that is a link is counted as left`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val frames = Files.createDirectories(root.resolve("problems/zz")).resolve("frames.jsonl")
        aLink(root.resolve(".ps/raw/$A_SESSION"), Files.writeString(frames, """{"n":1}""" + "\n"))
        val log = logGuardedBy(aStateDirectory(root))

        log.unprocessed()

        log.unreplayed() shouldBe LeftUnreplayed(1, uncounted = false)
    }

    /** Nothing is listed through a link, so nothing is counted there: the readers are told it may hold some. */
    @Test
    fun `a raw directory that was not listed may hold sessions, uncounted`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.createDirectories(root.resolve("problems/zz"))
        Files.writeString(elsewhere.resolve(A_SESSION), """{"n":1}""" + "\n")
        aLink(root.resolve(".ps/raw"), elsewhere)
        val log = logGuardedBy(aStateDirectory(root))

        log.unprocessed()

        log.unreplayed() shouldBe LeftUnreplayed(0, uncounted = true)
    }

    /** Each start answers for itself: once the work list is replayed whole, nothing is said to be left. */
    @Test
    fun `a work list replayed whole leaves nothing, though an earlier start left some`() {
        val git = ChangingAnswer(true)
        aSessionLeftBehind()
        val log = logGuardedBy(aStateDirectory(root, git))
        log.unprocessed()
        git.answer = false

        log.unprocessed() shouldHaveSize 1

        log.unreplayed() shouldBe LeftUnreplayed.NOTHING
    }

    @Test
    fun `git is not asked what it has ever tracked when nothing waits to be replayed`() {
        Files.createDirectories(root.resolve(".ps/raw"))
        val git = ChangingAnswer(false)

        logGuardedBy(aStateDirectory(root, git)).unprocessed().shouldBeEmpty()

        git.historyAsked shouldBe 0
    }

    // A session's verdict holds for its own grading, and the next one asks again (#377) ----------

    /**
     * Whether `.ps` may be written is asked at a session's first frame and kept for that grading: asking at
     * every frame would cost a git call each, a median of 7–8 ms on the host against 0.03 ms for the append.
     * Every frame still checks its own path, so a link swapped in between two frames is not written through
     * (the swap test above): a stat, then an open that follows no link at the file.
     * What a pull can change unseen is git's answer: the grading in flight finishes in its own file, which
     * git does not track, and the next session asks again and is held. What is held is not lost.
     */
    @Test
    fun `git tracking something mid-grading is seen by the next session, not by the one in flight`() {
        val git = ChangingAnswer(false)
        val log = logGuardedBy(aStateDirectory(root, git))
        val inFlight = log.start(120804)
        log.append(inFlight, """{"n":1}""")
        Files.writeString(root.resolve(".ps/raw/pulled.jsonl"), "{}\n")
        git.answer = true

        log.append(inFlight, """{"n":2}""")
        val next = log.start(120805)
        log.append(next, """{"m":1}""")
        val copied = log.complete(next, root.resolve("problems/120805-y/attempts/001.raw.jsonl"))

        Files.readAllLines(stateRaw(inFlight)) shouldContainExactly listOf("""{"n":1}""", """{"n":2}""")
        namesIn(root.resolve(".ps/raw")) shouldContainExactly listOf(inFlight.value, "pulled.jsonl")
        Files.readAllLines(copied) shouldContainExactly listOf("""{"m":1}""")
    }

    /** A session as a crash leaves it, or a pull delivers it: a file under `.ps/raw` the work list parses. */
    private fun aSessionLeftBehind(lessonId: Long = 120804): Path {
        val log = FileRawSessionLog(root.resolve(".ps/raw"), Clock.fixed(startedAt, ZoneOffset.UTC))
        val session = log.start(lessonId)
        log.append(session, """{"n":1}""")
        return root.resolve(".ps/raw").resolve(session.value)
    }

    // What another process could do while git answers: the raw directory moved away, a link left in its place.
    private fun swapForALink(raw: Path, target: Path) {
        if (Files.isSymbolicLink(raw)) return
        Files.move(raw, raw.resolveSibling("moved-away"))
        aLink(raw, target)
    }

    private fun logGuardedBy(state: StateDirectory) =
        FileRawSessionLog.under(root, Clock.fixed(startedAt, ZoneOffset.UTC), state)

    private fun stateRaw(session: RawSessionId): Path = root.resolve(".ps/raw").resolve(session.value)

    /** One instant, so every session opened with it collides unless the log prevents it. */
    private val sameMillisecond: Instant = Instant.parse("2026-08-11T05:26:42.748Z")

    private companion object {
        /** The name a session opened at `startedAt` for lesson 120804 gets: one the work list parses. */
        const val A_SESSION = "20260805T142301123Z-120804.jsonl"
    }
}
