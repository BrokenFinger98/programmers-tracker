package com.brokenfinger.tracker.domain.calc.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The one place the §7.1 example-value format is parsed.
 *
 * The wire carries JSON-*like* literals: `3, 2` is an argument list once bracket-wrapped and
 * `"4 5"` is a quoted stdin text. But they are not strictly JSON — a main-style expected
 * output carries a **raw newline inside its quotes** (measured on 181951), which every strict
 * parser rejects. Handled here once, by escaping raw control characters *inside quoted
 * regions* before parsing; a value that still fails returns null, and the generator refuses
 * on null rather than approximating — an approximation compiles and tests the wrong thing.
 */
object ExampleValues {
    private val json = Json

    /** `"3, 2"` → the argument list, or null when it is not one. */
    fun arguments(raw: String): List<JsonElement>? = parse("[$raw]")?.let { (it as? JsonArray)?.toList() }

    /** One value — an expected return, a stdin text — or null when unparseable. */
    fun single(raw: String): JsonElement? = parse(raw)

    private fun parse(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(escapeControlInQuotes(text)) }.getOrNull()?.takeIf { wellFormed(it) }

    /**
     * Strict parsing is not enough on its own: kotlinx-serialization accepts **bare
     * unquoted tokens** even in its non-lenient mode, handing back a primitive whose
     * `content` is the raw text. A §7.1 value is never such a token — it is a quoted
     * string, a number, a boolean, `null`, or an array of those — and the generators that
     * emit a value's text directly (JavaScript, Python) turned one into executable code in
     * a file the user is told to run (#92). The five typed generators already refused it by
     * coercing; refusing here makes all seven agree, at the one place §7.1 is parsed.
     */
    private fun wellFormed(value: JsonElement): Boolean = when (value) {
        is JsonNull -> true
        is JsonArray -> value.all { wellFormed(it) }
        is JsonObject -> value.values.all { wellFormed(it) }
        is JsonPrimitive -> value.isString || value.booleanOrNull != null || value.doubleOrNull != null
    }

    /**
     * Escapes raw control characters, but only inside quoted regions — a newline *between*
     * values is legal JSON whitespace and must stay one. Quote tracking honours backslash
     * escapes, so a `\"` inside a string does not flip the state.
     */
    private fun escapeControlInQuotes(text: String): String {
        val out = StringBuilder(text.length + 8)
        var inQuotes = false
        var escaped = false
        for (ch in text) {
            when {
                escaped -> {
                    out.append(ch)
                    escaped = false
                }
                ch == '\\' && inQuotes -> {
                    out.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    out.append(ch)
                    inQuotes = !inQuotes
                }
                inQuotes && ch == '\n' -> out.append("\\n")
                inQuotes && ch == '\r' -> out.append("\\r")
                inQuotes && ch == '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
