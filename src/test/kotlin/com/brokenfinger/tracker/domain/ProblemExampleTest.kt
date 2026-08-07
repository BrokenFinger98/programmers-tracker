package com.brokenfinger.tracker.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * One example the judge runs on Run Code — the pair the runner (#37) is generated from.
 *
 * Received leniently (`ofReceived`, dev rules §4): the values come off the wire, a field may
 * be missing, and losing a whole capture to one malformed example would cost more than the
 * example is worth. An entry with neither side is dropped; an entry with one side is kept,
 * because half a measured example still says something the runner generator can refuse on.
 */
class ProblemExampleTest {
    @Test
    fun `a complete pair is kept as received`() {
        val examples = ProblemExample.ofReceived(listOf("3, 2" to "1"))

        examples shouldBe listOf(ProblemExample(input = "3, 2", expected = "1"))
    }

    /**
     * Measured on 181951 (protocol §7.1): a main-style expected output carries a raw newline
     * inside the value. It is data, not a delimiter — it must survive verbatim.
     */
    @Test
    fun `a raw newline inside a value survives verbatim`() {
        val examples = ProblemExample.ofReceived(listOf("\"4 5\"" to "\"a = 4\nb = 5\""))

        examples.single().expected shouldBe "\"a = 4\nb = 5\""
    }

    @Test
    fun `an entry missing one side is kept, because half a measurement is still one`() {
        val examples = ProblemExample.ofReceived(listOf("3, 2" to null))

        examples shouldBe listOf(ProblemExample(input = "3, 2", expected = null))
    }

    @Test
    fun `an entry with neither side is dropped rather than stored as an empty shell`() {
        ProblemExample.ofReceived(listOf(null to null)).shouldBeEmpty()
    }

    @Test
    fun `order is the judge's order — index n here is index n in the result frames`() {
        val examples = ProblemExample.ofReceived(listOf("1" to "a", "2" to "b"))

        examples.map { it.input } shouldBe listOf("1", "2")
    }
}
