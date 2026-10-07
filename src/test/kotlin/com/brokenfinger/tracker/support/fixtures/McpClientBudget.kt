package com.brokenfinger.tracker.support.fixtures

/**
 * How many characters of a tool description, or of the server instructions, this server lets itself send.
 *
 * Claude Code cuts both at 2,048 characters and keeps the head. Its changelog says so twice: 2.1.84, "MCP
 * tool descriptions and server instructions are now capped at 2KB to prevent OpenAPI-generated servers from
 * bloating context", and 2.1.280, which "added `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` to change the
 * 2,048-character cap on MCP tool descriptions and server instructions for every MCP server in the session".
 * Left unset, everything past character 2,048 never reaches the model, and what is last in a text is lost
 * first — which was a reading, then a hand-over, then the end of an `incompleteHistory` warning.
 *
 * The budget is 2,000: 48 under the cap, room for a client that counts a little differently from us. A text
 * under the cap by that much needs no setting on the reader's side, which is the only guarantee a public
 * server can make.
 */
const val MCP_TEXT_BUDGET = 2_000
