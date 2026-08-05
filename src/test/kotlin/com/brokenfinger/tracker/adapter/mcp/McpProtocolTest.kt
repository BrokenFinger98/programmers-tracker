package com.brokenfinger.tracker.adapter.mcp

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test

class McpProtocolTest {
    /** Every revision this server names is a dated MCP revision, not a marketing version. */
    @Test
    fun `names every revision in the protocol's own date format`() {
        (McpProtocol.LEGACY + McpProtocol.MODERN).forEach { it.shouldMatch(Regex("""\d{4}-\d{2}-\d{2}""")) }
    }

    /**
     * A modern client can only reach the modern revision — the handshake ones are reachable
     * through `initialize` and nothing else, so advertising them here would invite a retry
     * that could only fail again.
     */
    @Test
    fun `advertises only the modern revision to a modern client`() {
        McpProtocol.MODERN_SUPPORTED.shouldContainExactly(McpProtocol.MODERN)
        McpProtocol.MODERN_SUPPORTED.shouldNotContain("2025-11-25")
    }

    /** `2025-03-26` predates `structuredContent`, which every tool here returns. */
    @Test
    fun `does not offer a handshake revision older than structured content`() {
        McpProtocol.LEGACY.shouldContainExactly("2025-11-25", "2025-06-18")
    }

    @Test
    fun `negotiates the handshake revision the client asked for`() {
        McpProtocol.negotiatedLegacy("2025-06-18") shouldBe "2025-06-18"
        McpProtocol.negotiatedLegacy("2025-11-25") shouldBe "2025-11-25"
    }

    @Test
    fun `falls back to its newest handshake revision for anything else`() {
        McpProtocol.negotiatedLegacy("2024-11-05") shouldBe "2025-11-25"
        McpProtocol.negotiatedLegacy(null) shouldBe "2025-11-25"
        McpProtocol.negotiatedLegacy("") shouldBe "2025-11-25"
        // Even the modern revision: it is not something an `initialize` can negotiate.
        McpProtocol.negotiatedLegacy(McpProtocol.MODERN) shouldBe "2025-11-25"
    }

    /**
     * Taken from the jar manifest. A run from a classes directory has none, and says so
     * rather than inventing a number that would read like a measurement.
     */
    @Test
    fun `reports a version rather than inventing one`() {
        McpProtocol.version().shouldNotBeBlank()
    }

    @Test
    fun `uses the reserved metadata keys exactly as the specification spells them`() {
        McpProtocol.META_PROTOCOL_VERSION shouldBe "io.modelcontextprotocol/protocolVersion"
        McpProtocol.META_CLIENT_CAPABILITIES shouldBe "io.modelcontextprotocol/clientCapabilities"
        McpProtocol.META_SERVER_INFO shouldBe "io.modelcontextprotocol/serverInfo"
    }

    /** A record repository is one person's solving history; no shared intermediary may hold it. */
    @Test
    fun `never lets a list result be cached publicly`() {
        McpProtocol.CACHE_SCOPE shouldBe "private"
    }

    /**
     * The codes we invent sit outside the JSON-RPC reserved range, because `-32000..-32019`
     * is closed to new allocations and `-32020..-32099` belongs to the specification.
     */
    @Test
    fun `numbers our own errors outside the range the specification governs`() {
        listOf(McpErrors.UNAUTHORIZED, McpErrors.FORBIDDEN_ORIGIN).forEach { code ->
            (code > -32000) shouldBe true
        }
    }

    @Test
    fun `uses the specification's codes with the specification's numbers`() {
        McpErrors.HEADER_MISMATCH shouldBe -32020
        McpErrors.UNSUPPORTED_PROTOCOL_VERSION shouldBe -32022
        McpErrors.METHOD_NOT_FOUND shouldBe -32601
        McpErrors.INVALID_PARAMS shouldBe -32602
    }
}
