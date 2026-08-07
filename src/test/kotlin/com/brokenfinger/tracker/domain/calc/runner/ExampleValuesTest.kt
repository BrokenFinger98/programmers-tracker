package com.brokenfinger.tracker.domain.calc.runner

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The one place the §7.1 wire format is parsed.
 *
 * The values are JSON-*like*: `3, 2` is an argument list once bracket-wrapped, `"4 5"` is a
 * quoted stdin text — but a main-style expected output carries a **raw newline inside its
 * quotes**, which strict JSON rejects. Every consumer that parsed these itself would trip on
 * exactly that, on exactly the multi-line problems, looking problem-specific. So it is
 * handled here once, and a value that still does not parse returns null — the generator
 * refuses on null rather than guessing (#37).
 */
class ExampleValuesTest {
    @Test
    fun `a scalar argument list parses by bracket-wrapping`() {
        val args = ExampleValues.arguments("3, 2")!!

        args.map { it.jsonPrimitive.intOrNull } shouldBe listOf(3, 2)
    }

    /** Measured on the 468379 console: five arguments ending in a 2-D array. */
    @Test
    fun `a nested array argument survives comma-splitting because nothing splits`() {
        val args = ExampleValues.arguments("4, 5, 2, 2, [[0, 0], [3, 1]]")!!

        args.size shouldBe 5
        (args[4] as JsonArray).size shouldBe 2
    }

    @Test
    fun `a quoted string argument keeps its commas`() {
        val args = ExampleValues.arguments("\"a, b\", 3")!!

        args.size shouldBe 2
        args[0].jsonPrimitive.content shouldBe "a, b"
    }

    /** The §7.1 trap, measured on 181951: a raw newline inside the quotes is data. */
    @Test
    fun `a raw newline inside a quoted value parses after escaping`() {
        val value = ExampleValues.single("\"a = 4\nb = 5\"")!!

        value.jsonPrimitive.content shouldBe "a = 4\nb = 5"
    }

    @Test
    fun `a bare number parses as itself`() {
        ExampleValues.single("42")!!.jsonPrimitive.intOrNull shouldBe 42
    }

    @Test
    fun `an expected array parses as an array`() {
        ExampleValues.single("[2, 2]")!!.jsonArray.size shouldBe 2
    }

    /** Unparseable means refuse — never a guess that compiles and tests the wrong thing. */
    @Test
    fun `an unparseable value returns null rather than an approximation`() {
        ExampleValues.arguments("new int[]{1,2}").shouldBeNull()
        ExampleValues.single("{'python': 'dict'}").shouldBeNull()
    }

    @Test
    fun `a raw newline between arguments is not confused with one inside quotes`() {
        val args = ExampleValues.arguments("\"line1\nline2\", 7")!!

        args.size shouldBe 2
        args[0].jsonPrimitive.content shouldBe "line1\nline2"
        (args[1] as JsonPrimitive).intOrNull shouldBe 7
    }
}
