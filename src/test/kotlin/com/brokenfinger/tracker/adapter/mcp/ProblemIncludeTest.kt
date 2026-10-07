package com.brokenfinger.tracker.adapter.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ProblemIncludeTest {
    /** The schema enumerates these, in this order, so a client caching `tools/list` sees the same list every time. */
    @Test
    fun `its wire names are its lower-case names, in declaration order`() {
        ProblemInclude.wireNames().shouldContainExactly("code", "runs")
    }

    @Test
    fun `every value reads back from its own wire name`() {
        ProblemInclude.entries.forEach { ProblemInclude.from(it.wireName()) shouldBe it }
    }

    /** Lenient about the spelling a model happens to use, as `groupBy` and `verdict` are. */
    @Test
    fun `a wire name is read whatever its case and surrounding space`() {
        ProblemInclude.from(" RUNS ") shouldBe ProblemInclude.RUNS
        ProblemInclude.from("Code") shouldBe ProblemInclude.CODE
    }

    /** Strict about the value: one we cannot honour is refused, naming what is offered, and never dropped. */
    @Test
    fun `a value it does not offer is refused, and the refusal says what it takes`() {
        listOf("returned", "", "  ", "code,runs", "coded").forEach { raw ->
            val refused = shouldThrow<IllegalArgumentException> { ProblemInclude.from(raw) }

            refused.message.shouldContain("include")
            ProblemInclude.wireNames().forEach { refused.message.shouldContain(it) }
        }
    }
}
