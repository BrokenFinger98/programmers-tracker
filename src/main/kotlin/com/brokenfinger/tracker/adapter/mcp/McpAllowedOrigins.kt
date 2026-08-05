package com.brokenfinger.tracker.adapter.mcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * DNS-rebinding defence for the MCP endpoint, which the specification makes a **MUST**:
 * a server on loopback must validate `Origin`, because a page the user merely visits can
 * otherwise be rebound onto `127.0.0.1` and talk to it.
 *
 * **The default allowlist is empty, so any request carrying an `Origin` is refused.** A
 * native MCP client — Claude, Cursor, a local model runner — sends no `Origin` at all;
 * one that does is a browser, and a browser has no business reading a solving history.
 * The property exists so a browser-based client can be admitted deliberately rather than
 * by accident.
 *
 * This is defence in depth, not the defence: the token is what actually stops a rebound
 * page, since a cross-origin request cannot set `X-Tracker-Token` without a preflight the
 * server never approves.
 */
@Component
class McpAllowedOrigins(@Value("\${tracker.mcp.allowed-origins:}") configured: String) {
    private val allowed: Set<String> = configured.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    /** @throws McpFailure when the caller presents an origin that was not admitted. */
    fun verify(origin: String?) {
        if (origin == null) return
        if (origin in allowed) return
        throw McpFailure.forbiddenOrigin()
    }
}
