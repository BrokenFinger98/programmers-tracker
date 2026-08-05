package com.brokenfinger.tracker.adapter.mcp

/**
 * The MCP revisions this server speaks.
 *
 * MCP is a dated, versioned protocol and it changed shape underneath us: revision
 * `2026-07-28` **removed** the `initialize` handshake, protocol-level sessions and `ping`,
 * and moved the protocol version onto every request's `_meta`. Everything up to
 * `2025-11-25` still handshakes. The specification names the two halves *modern* and
 * *legacy* and defines a **dual-era** server that answers both; that is what this is,
 * because a modern-only server fails every client shipping today and a legacy-only one
 * fails every client shipping tomorrow.
 *
 * Read from the specification on 2026-08-06, not from memory — see the ADR
 * [[decisions/2026-08-06-mcp-read-slice]].
 */
object McpProtocol {
    /** Stateless: version and capabilities per request, `server/discover`, no handshake. */
    const val MODERN = "2026-07-28"

    /**
     * Handshake revisions, newest first — this is the list an `initialize` negotiates over.
     * `2025-03-26` is deliberately absent: it predates `structuredContent`, which every
     * tool here returns.
     */
    val LEGACY = listOf("2025-11-25", "2025-06-18")

    /**
     * What a *modern* client may ask for. Only [MODERN]: the legacy revisions are reachable
     * through `initialize` and nothing else, so listing them here would invite a retry that
     * could only fail again.
     */
    val MODERN_SUPPORTED = listOf(MODERN)

    const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
    const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

    const val NAME = "programmers-tracker"

    /**
     * How long a client may cache a list result. The tool set is fixed at compile time, so
     * this is a statement about the build rather than a guess about the data.
     */
    const val LIST_TTL_MS = 3_600_000L

    /** The record repository is one person's solving history; no shared cache may hold it. */
    const val CACHE_SCOPE = "private"

    /**
     * Self-reported, per the specification's own note, and taken from the jar manifest.
     * A run from a classes directory has no manifest and says so rather than inventing a
     * number that would read like a measurement.
     */
    fun version(): String = McpProtocol::class.java.`package`?.implementationVersion ?: "unknown"

    fun negotiatedLegacy(requested: String?): String = LEGACY.firstOrNull { it == requested } ?: LEGACY.first()
}

/**
 * JSON-RPC and MCP error codes.
 *
 * The specification partitions the JSON-RPC server-error range: `-32000..-32019` is closed
 * to new allocations and `-32020..-32099` belongs to the specification itself. Codes we
 * invent therefore live outside the reserved range entirely, which is what the spec's
 * error-code policy asks new implementations to do.
 */
object McpErrors {
    const val PARSE = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL = -32603

    /** Spec-defined: the HTTP headers disagree with the body, or a required one is missing. */
    const val HEADER_MISMATCH = -32020

    /** Spec-defined: the revision the request declares is not one we implement. */
    const val UNSUPPORTED_PROTOCOL_VERSION = -32022

    /** Ours, outside the reserved range: local authorization, which MCP does not define. */
    const val UNAUTHORIZED = -31001
    const val FORBIDDEN_ORIGIN = -31002
}
