package com.brokenfinger.tracker.adapter.mcp

import kotlinx.serialization.json.JsonPrimitive

/**
 * What the tool path and the prompt path say alike about the arguments they were given, worded once so the two cannot
 * drift apart. Each kept its own copy before #365, and the tool path's had fallen behind.
 */
object McpArguments {
    /**
     * The refusal of [keys] that [owner] does not take. A key is the client's text, so each is quoted as a JSON string:
     * "a, b" stays one item and a newline cannot break the message. The keys are sorted, so a call is refused in the
     * same words whatever order its keys came in. What [owner] takes follows in the order given, which is the order
     * its listing shows a client, so the refusal reads like the documentation.
     */
    fun unknown(owner: String, keys: Set<String>, taken: List<String>): String {
        val received = keys.sorted().joinToString { JsonPrimitive(it).toString() }
        return "unknown argument(s): $received; $owner ${takes(taken)}"
    }

    private fun takes(taken: List<String>): String {
        if (taken.isEmpty()) return "takes no arguments"
        return "takes ${taken.joinToString()}"
    }
}
