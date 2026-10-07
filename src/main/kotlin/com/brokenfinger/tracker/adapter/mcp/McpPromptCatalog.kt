package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The prompts this server offers — one, [ExamPrepPrompt].
 *
 * **The argument order is an interface.** Claude Code maps the words after a slash command onto these
 * arguments in the order listed, split on spaces and without quoting (2.1.285, read from the client).
 * So `language` comes first, the one most sessions give, and `part` last. Most part names contain a
 * space, so a client that splits on spaces hands over only the first word of one. In last place the
 * words after it fall off the end; in any other place they would land in the next argument. With the
 * order (language, part, since), the "BY" of "GROUP BY" would become `since` and be refused
 * ([[decisions/2026-10-07-exam-prep-asks-in-the-open]]).
 *
 * A refusal is `-32602`, the code the specification gives an unknown prompt and a bad argument.
 */
object McpPromptCatalog {
    val NAMES = listOf(ExamPrepPrompt.NAME)

    private data class Argument(val name: String, val description: String)

    /** In the order Claude Code fills them, each with what it narrows. */
    private val EXAM_PREP_ARGUMENTS = listOf(
        Argument("language", "A Programmers language id (java, python3, mysql, …). Narrows repair_steps."),
        Argument("since", "${Since.FORMAT}. Narrows repair_steps to corrections recorded from then on."),
        Argument(
            "part",
            "A part name, matched in full and case-insensitively. Narrows repair_steps. Last, because a client " +
                "that splits on spaces keeps only its first word.",
        ),
    )

    fun definitions(): JsonArray = buildJsonArray { add(examPrep()) }

    /** The `prompts/get` answer, or a refusal already shaped for the client. */
    fun get(name: String?, arguments: JsonObject): JsonObject {
        if (name != ExamPrepPrompt.NAME) throw refused("unknown prompt; this server offers ${NAMES.joinToString()}")
        return answer(ExamPrepPrompt.text(scopeOf(arguments)))
    }

    private fun scopeOf(arguments: JsonObject): ExamPrepScope {
        val unknown = arguments.keys - EXAM_PREP_ARGUMENTS.map { it.name }.toSet()
        if (unknown.isNotEmpty()) throw refused("unknown argument(s): ${unknown.sorted().joinToString()}")
        return try {
            readScope(arguments)
        } catch (invalid: IllegalArgumentException) {
            throw refused(invalid.message ?: "the arguments could not be used")
        }
    }

    // Named, because three String? in a row would compile in any order.
    private fun readScope(arguments: JsonObject): ExamPrepScope = ExamPrepScope(
        language = arguments.given("language"),
        since = arguments.given("since"),
        part = arguments.given("part"),
    )

    // Strings, by the specification, refused in the tools' words otherwise. A blank one is not given —
    // where a tool refuses a blank — because a client that shows arguments as a form sends an empty
    // field as "". Values are trimmed as they are read here; the tools trim when they match.
    private fun JsonObject.given(name: String): String? {
        val value = this[name]
        if (value == null || value is JsonNull) return null
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$name must be text")
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private fun answer(text: String): JsonObject = buildJsonObject {
        put("description", DESCRIPTION)
        put("messages", JsonArray(listOf(userText(text))))
    }

    // Claude Code merges every message into one and ignores `role`, so one user message is the whole answer.
    private fun userText(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonObject("content") {
            put("type", "text")
            put("text", text)
        }
    }

    private fun examPrep(): JsonObject = buildJsonObject {
        put("name", ExamPrepPrompt.NAME)
        put("title", "Exam prep from your own repair steps")
        put("description", DESCRIPTION)
        put("arguments", JsonArray(EXAM_PREP_ARGUMENTS.map { (name, description) -> argument(name, description) }))
    }

    private fun argument(name: String, description: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("required", false)
    }

    // The dispatcher carries -32602 on 400 to a modern client and on 200 to a handshake one, as for an unknown tool.
    private fun refused(message: String) = McpFailure(McpErrors.INVALID_PARAMS, 400, message)

    private const val DESCRIPTION = "Before a coding test: your recurring mistakes, found by your own model in " +
        "your repair steps and cited by record id, with problems to re-solve, untouched ones in the same parts, " +
        "and drills aimed at each."
}
