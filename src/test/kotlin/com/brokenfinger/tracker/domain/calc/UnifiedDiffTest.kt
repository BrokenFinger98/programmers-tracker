package com.brokenfinger.tracker.domain.calc

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks (dev rules §6.1). One implementation serves the attempt diffs written into records and
 * the repair-step diffs served over MCP, so the two cannot format a change differently.
 */
class UnifiedDiffTest {
    @Test
    fun `a replaced line reads as a removal then an addition, with context around it`() {
        val diff = UnifiedDiff.of(listOf("a", "b", "c"), listOf("a", "x", "c"), "from", "to")

        diff shouldBe "--- a/from\n+++ b/to\n@@ -1,3 +1,3 @@\n a\n-b\n+x\n c"
    }

    @Test
    fun `identical lines have no diff`() {
        UnifiedDiff.of(listOf("a", "b"), listOf("a", "b"), "from", "to").shouldBeNull()
    }

    @Test
    fun `an empty old side is all additions`() {
        UnifiedDiff.of(emptyList(), listOf("a", "b"), "from", "to") shouldBe
            "--- a/from\n+++ b/to\n@@ -0,0 +1,2 @@\n+a\n+b"
    }

    @Test
    fun `an empty new side is all removals`() {
        UnifiedDiff.of(listOf("a", "b"), emptyList(), "from", "to") shouldBe
            "--- a/from\n+++ b/to\n@@ -1,2 +0,0 @@\n-a\n-b"
    }

    @Test
    fun `changes far apart are separate hunks`() {
        val old = (1..10).map { "line $it" }
        val new = listOf("first") + old.subList(1, 9) + "last"

        val diff = UnifiedDiff.of(old, new, "from", "to")!!

        diff.lines().count { it.startsWith("@@") } shouldBe 2
    }

    @Test
    fun `a diff longer than the cap is cut off and says so`() {
        val diff = UnifiedDiff.of(emptyList(), (1..500).map { "n$it" }, "from", "to")!!.lines()

        diff.size shouldBe UnifiedDiff.MAX_LINES + 1
        diff.last() shouldBe "... diff truncated at ${UnifiedDiff.MAX_LINES} lines"
    }

    /** The text is also what attempt files' `diffFromPrev` already carry, so it is pinned and does not drift. */
    @Test
    fun `the cap ends a diff with the truncation marker`() {
        val diff = UnifiedDiff.of(emptyList(), (1..500).map { "n$it" }, "from", "to")!!

        UnifiedDiff.TRUNCATION_MARKER shouldBe "... diff truncated at 400 lines"
        diff.lines().last() shouldBe UnifiedDiff.TRUNCATION_MARKER
    }

    @Test
    fun `a diff cut at the cap is recognised as truncated`() {
        val capped = UnifiedDiff.of(emptyList(), (1..500).map { "n$it" }, "from", "to")!!

        UnifiedDiff.isTruncated(capped) shouldBe true
    }

    @Test
    fun `a diff under the cap is not truncated, and neither is one exactly at it`() {
        // Two header lines, one hunk header and 397 additions make exactly MAX_LINES lines.
        val atCap = UnifiedDiff.of(emptyList(), (1..397).map { "n$it" }, "from", "to")!!
        val small = UnifiedDiff.of(listOf("a"), listOf("b"), "from", "to")!!

        atCap.lines().size shouldBe UnifiedDiff.MAX_LINES
        UnifiedDiff.isTruncated(atCap) shouldBe false
        UnifiedDiff.isTruncated(small) shouldBe false
    }

    /** A line of the code being diffed always carries a marker character, so it cannot pass for the cap's own. */
    @Test
    fun `a changed line that reads like the marker is not truncation`() {
        val diff = UnifiedDiff.of(emptyList(), listOf(UnifiedDiff.TRUNCATION_MARKER), "from", "to")!!

        UnifiedDiff.isTruncated(diff) shouldBe false
    }

    @Test
    fun `a side too large to diff cheaply yields no diff`() {
        val huge = List(UnifiedDiff.MAX_INPUT_LINES + 1) { "x$it" }

        UnifiedDiff.fits(huge, emptyList()) shouldBe false
        UnifiedDiff.fits(emptyList(), huge) shouldBe false
        UnifiedDiff.of(huge, emptyList(), "from", "to").shouldBeNull()
    }
}
