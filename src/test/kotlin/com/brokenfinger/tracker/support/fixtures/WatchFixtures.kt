package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.WatchCommand

/**
 * Object mothers for the `/watch` request (dev rules §6.4).
 *
 * The values are lesson 120804's, measured (design §4.1). Both fields are given as **raw
 * JSON fragments** so a test can express the string form a DOM attribute produces
 * (`"120804"`), the number form a hand-typed curl produces (`120804`), an explicit `null`,
 * or a wrong JSON kind — all through one builder.
 *
 * Two fields, since #114: the channel identifiers are properties of the problem and the
 * server reads them off its page, so a caller supplies only what the server cannot know.
 */
fun aWatchBody(
    lessonId: String = "\"120804\"",
    language: String = "\"java\"",
    omit: Set<String> = emptySet(),
    extra: Map<String, String> = emptyMap(),
): String {
    val fields = linkedMapOf("lessonId" to lessonId, "language" to language) + extra
    return fields.filterKeys { it !in omit }
        .entries
        .joinToString(prefix = "{", postfix = "}") { (name, value) -> "\"$name\":$value" }
}

fun aWatchCommand(lessonId: Long = 120804, language: String = "java") =
    WatchCommand(lessonId = lessonId, language = language)
