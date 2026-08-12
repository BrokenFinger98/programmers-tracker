package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.TagCount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TagNotesTest {
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
     * The file name is a path and the `tag:` field is the datum. They differ on purpose for a
     * tag the filesystem would not take verbatim, and only the field may be compared against a
     * record's tags.
     */
    @Test
    fun `a tag the filesystem would not take keeps its spelling in the field`() {
        notes().write(listOf(TagCount("prime factorization", catalogTotal = 2, attempted = 0, solved = 0)))

        val text = Files.readString(root.resolve("tags/prime-factorization.md"))
        text shouldContain "tag: prime factorization"
    }

    /** Nothing to write is not an error; a catalog we do not own may describe no tags at all. */
    @Test
    fun `an empty map writes nothing and does not fail`() {
        notes().write(emptyList())

        Files.exists(root.resolve("tags")) shouldBe false
    }

    /**
     * The edges between tags are what give the map shape before anything is solved. Without
     * them a live vault showed 81 of 83 tags isolated, and isolation says nothing when nearly
     * everything is isolated (#231).
     */
    @Test
    fun `the note links to the tags it shares problems with`() {
        notes().write(listOf(TagCount("dp", 38, 0, 0, related = listOf("implementation", "math"))))

        val text = Files.readString(root.resolve("tags/dp.md"))
        text shouldContain "[[tags/implementation]]"
        text shouldContain "[[tags/math]]"
    }

    /** A tag that shares a problem with nothing says nothing rather than showing an empty label. */
    @Test
    fun `a tag with no related tags renders no link line`() {
        notes().write(listOf(TagCount("tsp", 1, 0, 0, related = emptyList())))

        Files.readString(root.resolve("tags/tsp.md")) shouldNotContain "[[tags/"
    }
}
