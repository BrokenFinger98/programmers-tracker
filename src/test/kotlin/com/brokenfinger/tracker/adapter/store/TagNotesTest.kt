package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.domain.calc.TouchedProblem
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TagNotesTest {
    /** Any wikilink, alias stripped — the target is what has to resolve. */
    /** `[text](relative/path.md)` — the format every renderer understands, not just Obsidian (#293). */
    private val link = Regex("""\[[^\]]*]\(([^)]+)\)""")

    @TempDir
    lateinit var root: Path

    private fun notes() = TagNotes(RecordLayout(root))

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
}
