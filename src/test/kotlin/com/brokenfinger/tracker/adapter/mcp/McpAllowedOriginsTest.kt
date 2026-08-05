package com.brokenfinger.tracker.adapter.mcp

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class McpAllowedOriginsTest {
    /** A native MCP client sends no `Origin` at all; that is the normal case. */
    @Test
    fun `admits a request that carries no origin`() {
        shouldNotThrowAny { McpAllowedOrigins("").verify(null) }
    }

    /**
     * The DNS-rebinding defence the specification makes a MUST. Nothing is allowed by
     * default, so a page the user merely visited cannot be rebound onto loopback and read
     * a solving history.
     */
    @Test
    fun `refuses every browser origin by default`() {
        listOf("https://evil.example", "http://localhost:3000", "null").forEach { origin ->
            shouldThrow<McpFailure> { McpAllowedOrigins("").verify(origin) }.status shouldBe 403
        }
    }

    @Test
    fun `admits an origin that was configured deliberately`() {
        shouldNotThrowAny { McpAllowedOrigins("https://studio.example").verify("https://studio.example") }
    }

    @Test
    fun `still refuses an origin that was not configured`() {
        shouldThrow<McpFailure> { McpAllowedOrigins("https://studio.example").verify("https://evil.example") }
    }

    @Test
    fun `reads a comma-separated list, whitespace and all`() {
        val origins = McpAllowedOrigins(" https://a.example , https://b.example ")

        shouldNotThrowAny { origins.verify("https://a.example") }
        shouldNotThrowAny { origins.verify("https://b.example") }
        shouldThrow<McpFailure> { origins.verify("https://c.example") }
    }

    @Test
    fun `an empty entry never admits an empty origin header`() {
        shouldThrow<McpFailure> { McpAllowedOrigins("https://a.example,,").verify("") }
    }

    @Test
    fun `refuses with a message that does not echo what the caller sent`() {
        val thrown = shouldThrow<McpFailure> { McpAllowedOrigins("").verify("https://evil.example") }

        thrown.code shouldBe McpErrors.FORBIDDEN_ORIGIN
        thrown.message.contains("evil.example") shouldBe false
    }
}
