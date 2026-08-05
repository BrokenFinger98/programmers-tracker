package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.WatchCommand
import com.brokenfinger.tracker.domain.ProblemKind

/**
 * Object mothers for the `/watch` request (dev rules §6.4).
 *
 * The values are the measured identifiers of lesson 120804 (design §4.1). Every field is
 * given as a **raw JSON fragment** so a test can express the string form the extension
 * actually sends (`"120804"`), the number form (`120804`), an explicit `null`, or a wrong
 * JSON kind — all through the same builder.
 */
fun aWatchBody(
    lessonId: String = "\"120804\"",
    challengeableId: String = "\"14643\"",
    challengeableType: String = "\"algorithm\"",
    language: String = "\"java\"",
    codesKey: String = "\"49598\"",
    omit: Set<String> = emptySet(),
): String {
    val fields = linkedMapOf(
        "lessonId" to lessonId,
        "challengeableId" to challengeableId,
        "challengeableType" to challengeableType,
        "language" to language,
        "codesKey" to codesKey,
    )
    return fields.filterKeys { it !in omit }
        .entries
        .joinToString(prefix = "{", postfix = "}") { (name, value) -> "\"$name\":$value" }
}

fun aWatchCommand(
    lessonId: Long = 120804,
    challengeableId: Long = 14643,
    kind: ProblemKind = ProblemKind.ALGORITHM,
    language: String = "java",
    codesKey: String = "49598",
) = WatchCommand(
    lessonId = lessonId,
    challengeableId = challengeableId,
    kind = kind,
    language = language,
    codesKey = codesKey,
)
