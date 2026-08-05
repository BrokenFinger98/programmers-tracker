package com.brokenfinger.tracker.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RawSessionIdTest {
    @Test
    fun `accepts a plain file name`() {
        RawSessionId("20260805T142301123Z-120804.jsonl").value shouldBe "20260805T142301123Z-120804.jsonl"
    }

    @Test
    fun `rejects a blank id`() {
        shouldThrow<IllegalArgumentException> { RawSessionId("  ") }
    }

    @Test
    fun `rejects path separators so an id can never escape the raw directory`() {
        shouldThrow<IllegalArgumentException> { RawSessionId("sub/dir.jsonl") }
        shouldThrow<IllegalArgumentException> { RawSessionId("sub\\dir.jsonl") }
    }

    @Test
    fun `rejects parent traversal`() {
        shouldThrow<IllegalArgumentException> { RawSessionId("..") }
        shouldThrow<IllegalArgumentException> { RawSessionId("..-120804.jsonl") }
    }

    @Test
    fun `rejects characters Windows forbids in a file name`() {
        listOf("a:b.jsonl", "a?b.jsonl", "a*b.jsonl", "a|b.jsonl", "a<b.jsonl").forEach {
            shouldThrow<IllegalArgumentException> { RawSessionId(it) }
        }
    }
}
