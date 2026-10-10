package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.domain.calc.TouchedProblem
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.flagged
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.fixtures.unwritableWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class TagNotesTest {
    /** Any wikilink, alias stripped — the target is what has to resolve. */
    /** `[text](relative/path.md)` — the format every renderer understands, not just Obsidian (#293). */
    private val link = Regex("""\[[^\]]*]\(([^)]+)\)""")

    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private fun notes() = TagNotes(RecordLayout(root))

    // Written over a link, never through one (#361) ---------------------------------------------------

    @Test
    fun `a tag note that is a link is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val note = aLink(root.resolve("tags/dp.md"), token)

        val heard = warningsWhile(RecordWrites::class) { notes().write(listOf(DP)) }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.isSymbolicLink(note) shouldBe false
        Files.readString(note) shouldContain "tag: dp"
        heard.single() shouldContain note.toString()
    }

    /** The whole map is rewritten at every boot, so a linked directory is said once for it, not once a note. */
    @Test
    fun `a tags directory that is a link gets no notes, said once, and nothing is written where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("tags"), outside)

        val heard = warningsWhile(RecordWrites::class) { notes().write(listOf(DP, GREEDY)) }

        namesIn(outside).shouldBeEmpty()
        heard.single() shouldContain "tags is a symbolic link"
    }

    @Test
    fun `a tag note that is a dangling link is replaced, and nothing is created where it pointed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-note.md")
        val note = aLink(root.resolve("tags/dp.md"), nowhere)

        notes().write(listOf(DP))

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.readString(note) shouldContain "tag: dp"
    }

    // A note the file system will not take (#407's review) --------------------------------------------

    /**
     * A note whose replace failed for anything but a refusal threw out of the loop, so every note after it kept what
     * it held until a later boot. Each is skipped now, and said once by its own path, so every note is tried.
     */
    @Test
    fun `notes the file system will not take are each skipped, and said once by their own path`() {
        assumeTrue(keepsPosixPermissions(root), "this test closes a directory to writes")
        val tags = aNoteWritten().parent
        val notes = notes()

        val heard = warningsWhile(RecordWrites::class) {
            unwritableWhile(tags) {
                assumeTrue(!Files.isWritable(tags), "a superuser writes anyway")
                repeat(2) { notes.write(listOf(DP, GREEDY)) }
            }
        }

        heard.size shouldBe 2
        heard.first() shouldContain tags.resolve("dp.md").toString()
        heard.last() shouldContain tags.resolve("greedy.md").toString()
    }

    /** One note the file system will not replace, the others writable: immutable, as `chflags uchg` makes it. */
    @Test
    fun `a note that cannot be replaced keeps its bytes, and the note after it is written`() {
        val stuck = aNoteWritten()
        assumeTrue(flagged(stuck, "uchg"), "no chflags on this machine")

        val heard = try {
            refreshedPastTheStuckNote()
        } finally {
            flagged(stuck, "nouchg")
        }

        skippedAlone(stuck, heard)
    }

    /** The same on Windows, where the note is held open by another process, as #407 measured for a page. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a note held open by another process keeps its bytes, and the note after it is written`() {
        val stuck = aNoteWritten()

        val heard = FileInputStream(stuck.toFile()).use { refreshedPastTheStuckNote() }

        skippedAlone(stuck, heard)
    }

    @Test
    fun `writes one note per tag, with the denominator that makes it honest`() {
        notes().write(listOf(TagCount("dp", catalogTotal = 38, attempted = 5, solved = 3)))

        val text = Files.readString(root.resolve("tags/dp.md"))
        text shouldContain "tag: dp"
        text shouldContain "catalogTotal: 38"
        text shouldContain "attempted: 5"
        text shouldContain "solved: 3"
    }

    /**
     * The word the graph colours on. An Obsidian colour group takes a search query rather than an
     * expression, so `["status":"attempted"]` is the difference between a setting the owner can
     * type and the two-clause negation "tried and not passed" needed without it (#250).
     */
    @Test
    fun `the note says where you stand, so a colour group can be one exact match`() {
        notes().write(
            listOf(
                TagCount("passed", 10, 1, 1),
                TagCount("tried", 10, 1, 0),
                TagCount("never", 10, 0, 0),
            ),
        )

        Files.readString(root.resolve("tags/passed.md")) shouldContain "status: passed"
        Files.readString(root.resolve("tags/tried.md")) shouldContain "status: attempted"
        Files.readString(root.resolve("tags/never.md")) shouldContain "status: untouched"
    }

    /**
     * The untouched tag is the point of the map: its note exists so the graph has a node to
     * leave isolated. A view built from records alone could not produce this file.
     */
    @Test
    fun `an untouched tag gets its note too`() {
        notes().write(listOf(TagCount("tsp", catalogTotal = 1, attempted = 0, solved = 0)))

        Files.exists(root.resolve("tags/tsp.md")) shouldBe true
    }

    /** Rewriting an unchanged map must leave git nothing to see. */
    @Test
    fun `a second write of the same counts produces identical bytes`() {
        val counts = listOf(TagCount("dp", 38, 5, 3), TagCount("math", 78, 1, 0))

        notes().write(counts)
        val first = Files.readAllBytes(root.resolve("tags/dp.md"))
        notes().write(counts)

        Files.readAllBytes(root.resolve("tags/dp.md")) shouldBe first
    }

    /**
     * The `tag:` field is the datum and keeps its spelling; the file name is a path and is
     * slugged. That much this test always asserted — and it stopped there, which is how 43 of
     * the catalog's 83 tags shipped with every link to them broken (#233).
     *
     * A link is a **path**, so it has to use the slug. Asserting the two spellings differ says
     * nothing about which one a link needs; the test below asks that question by resolving the
     * link against the files actually on disk.
     */
    @Test
    fun `the field keeps the tag spelling and the link uses the file name`() {
        notes().write(listOf(TagCount("prime factorization", 2, 1, 1, touched = listOf(aTouch(1, "두 수의 합")))))

        val text = Files.readString(root.resolve("tags/prime-factorization.md"))
        text shouldContain "tag: prime factorization"
        text shouldContain "[두 수의 합](../problems/1-두-수의-합/README.md)"
    }

    /**
     * The check that was missing. Every link any writer emits must name a file that exists —
     * asserted against the directory rather than against the naming rule, because a test that
     * restates the rule agrees with whatever the rule currently is.
     */
    @Test
    fun `every link a note emits resolves to a file that exists`() {
        writeProblemPage(1, "두 수의 합")
        writeProblemPage(2, "binary search 연습")
        val counts = listOf(
            TagCount("dp_digit", 1, 1, 1, touched = listOf(aTouch(1, "두 수의 합"))),
            TagCount("binary_search", 38, 1, 0, touched = listOf(aTouch(2, "binary search 연습", passed = false))),
        )

        notes().write(counts)

        val links = Files.list(root.resolve("tags")).use { paths ->
            paths.toList().flatMap { link.findAll(Files.readString(it)).map { m -> m.groupValues[1] } }
        }
        links.shouldNotBeEmpty()
        // Resolved from the note's own directory, which is what a relative link means and what
        // every renderer will do with it — a stricter check than the vault-root one it replaces.
        links.forEach { target -> Files.exists(root.resolve("tags").resolve(target).normalize()) shouldBe true }
    }

    /** The page a link points at, written the way [ProblemReadme] would name it. */
    private fun writeProblemPage(lessonId: Long, title: String) {
        val file = RecordLayout(root).problemDirectory(lessonId, title).resolve("README.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, "# $title\n")
    }

    private fun aTouch(lessonId: Long, title: String, passed: Boolean = true) =
        TouchedProblem(lessonId = lessonId, title = title, passed = passed)

    /** Nothing to write is not an error; a catalog we do not own may describe no tags at all. */
    @Test
    fun `an empty map writes nothing and does not fail`() {
        notes().write(emptyList())

        Files.exists(root.resolve("tags")) shouldBe false
    }

    /**
     * The edges between tags are what give the map shape before anything is solved. Without
     * them a live vault showed 81 of 83 tags isolated, and isolation says nothing when nearly
     * everything is isolated (#231) — which is why they are named here and not merely counted.
     */
    @Test
    fun `the note splits its problems into passed and not`() {
        val touched = listOf(aTouch(1, "solved one"), aTouch(2, "open one", passed = false))

        notes().write(listOf(TagCount("dp", 38, 2, 1, touched = touched)))

        val text = Files.readString(root.resolve("tags/dp.md"))
        text shouldContain "Passed: [solved one](../problems/1-solved-one/README.md)"
        text shouldContain "Attempted without a pass: [open one](../problems/2-open-one/README.md)"
    }

    /**
     * The reversal (#241). Obsidian sizes a node by its link count, so 27 catalog neighbours
     * against 2 solved problems made `implementation` the largest node on the map of someone who
     * had solved two problems. Nothing here may link a tag to a tag.
     */
    @Test
    fun `no note links to another tag`() {
        notes().write(listOf(TagCount("dp", 38, 1, 1, touched = listOf(aTouch(1, "p")))))

        Files.readString(root.resolve("tags/dp.md")) shouldNotContain "[[tags/"
    }

    /** A tag nothing has been submitted to shows no link line rather than an empty label. */
    @Test
    fun `an untouched tag renders no link line`() {
        notes().write(listOf(TagCount("tsp", 1, 0, 0)))

        Files.readString(root.resolve("tags/tsp.md")) shouldNotContain "[["
    }

    // The note for dp, as an earlier pass wrote it.
    private fun aNoteWritten(): Path {
        notes().write(listOf(DP))
        return root.resolve("tags/dp.md")
    }

    // Twice through one writer, with new counts for the stuck note, which comes first: what was said.
    private fun refreshedPastTheStuckNote(): List<String> {
        val notes = notes()
        return warningsWhile(RecordWrites::class) { repeat(2) { notes.write(listOf(DP_LATER, GREEDY)) } }
    }

    private fun skippedAlone(stuck: Path, heard: List<String>) {
        Files.readString(stuck) shouldContain "attempted: ${DP.attempted}"
        Files.readString(stuck.resolveSibling("greedy.md")) shouldContain "tag: greedy"
        heard.single() shouldContain stuck.toString()
        namesIn(stuck.parent) shouldBe listOf("dp.md", "greedy.md")
    }

    private companion object {
        val DP = TagCount("dp", catalogTotal = 38, attempted = 5, solved = 3)
        val GREEDY = TagCount("greedy", catalogTotal = 20, attempted = 1, solved = 0)

        /** The same tag met again since: what a note that was replaced would say instead. */
        val DP_LATER = TagCount("dp", catalogTotal = 38, attempted = 6, solved = 4)
    }
}
