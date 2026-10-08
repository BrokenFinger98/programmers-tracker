package com.brokenfinger.tracker.adapter.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the tool path and the prompt path say alike about the arguments they were given, worded once so the two cannot
 * drift apart. Each kept its own copy before #365, and the tool path's had fallen behind.
 */
object McpArguments {
    /**
     * The message refusing [keys] that [owner] does not take. A key is the client's text, so each is quoted as a JSON
     * string: "a, b" stays one item and a newline cannot break the message. The keys are sorted, so a call is refused
     * in the same words whatever order its keys came in. What [owner] takes follows in the order given, which is the
     * order its listing shows a client, so the refusal reads like the documentation.
     */
    fun unknownArgumentsMessage(owner: String, keys: Set<String>, taken: List<String>): String {
        val received = keys.sorted().joinToString { JsonPrimitive(it).toString() }
        return "unknown argument(s): $received; $owner ${takes(taken)}"
    }

    private fun takes(taken: List<String>): String {
        if (taken.isEmpty()) return "takes no arguments"
        return "takes ${taken.joinToString()}"
    }

    /**
     * [name] as text, checking its JSON type and nothing more. Absent or JSON null is "not given", never the text
     * "null". A JSON string is read as it came, blank and padding included, because what a blank means is each path's
     * to decide: a tool refuses it in its own words, a prompt reads it as not given. Anything else is refused under
     * the argument's name — a number is not the text of its digits, so `language: 5` is a mistake to say so, not the
     * language "5".
     */
    fun optionalText(arguments: JsonObject, name: String): String? {
        val raw = arguments[name]
        if (raw == null || raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$name must be text")
    }
}
