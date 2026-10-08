package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/** Said once per key for an instance (#386), which four classes each kept a set of their own for. */
class SaidOnceTest {
    @Test
    fun `a key is said the first time it comes, and never after`() {
        val heard = mutableListOf<String>()
        val once = SaidOnce()

        repeat(3) { once.say("a reason") { heard += "a reason" } }

        heard shouldContainExactly listOf("a reason")
    }

    @Test
    fun `each key is said once, in the order they come`() {
        val heard = mutableListOf<Any>()
        val once = SaidOnce()

        listOf("one", 2, "one", 2, Refusal.FIRST).forEach { key -> once.say(key) { heard += key } }

        heard shouldContainExactly listOf("one", 2, Refusal.FIRST)
    }

    /** A new instance — a writer built again, a server restarted — says it again: whoever reads that log has not seen it. */
    @Test
    fun `another instance says it again`() {
        val heard = mutableListOf<String>()

        SaidOnce().say("a reason") { heard += "first" }
        SaidOnce().say("a reason") { heard += "second" }

        heard shouldContainExactly listOf("first", "second")
    }

    private enum class Refusal { FIRST }
}
