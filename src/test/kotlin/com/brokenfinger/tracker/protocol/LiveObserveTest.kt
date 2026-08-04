package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.support.fixtures.aSqlIdentifier
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LiveObserveTest {
    // Arguments mirror the measured targets of protocol doc §15.
    @Test
    fun `parses an algorithm target`() {
        parseTarget(arrayOf("algorithm", "120804", "14643", "java")) shouldBe anAlgorithmIdentifier()
    }

    @Test
    fun `parses a database target`() {
        parseTarget(arrayOf("database", "131528", "2778", "mysql")) shouldBe aSqlIdentifier()
    }

    @Test
    fun `rejects a wrong argument count`() {
        parseTarget(arrayOf("algorithm", "120804")).shouldBeNull()
    }

    @Test
    fun `rejects an unsupported problem type`() {
        parseTarget(arrayOf("quiz", "120804", "14643", "java")).shouldBeNull()
    }

    @Test
    fun `rejects a non-numeric lesson id`() {
        parseTarget(arrayOf("algorithm", "abc", "14643", "java")).shouldBeNull()
    }
}
