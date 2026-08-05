package com.brokenfinger.tracker.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lesson number from the problem URL path (protocol doc §3). */
@JvmInline
value class LessonId(val value: Long) {
    init {
        require(value > 0) { "lessonId must be positive: $value" }
    }
}

/**
 * data-challengeable-id of the problem page — language-independent (protocol doc §3).
 * Distinct type from [CodesKey]: sending the codes key as challengeable_id passes the
 * subscription and testcases but silently fails result finalization (§3 trap).
 */
@JvmInline
value class ChallengeableId(val value: Long) {
    init {
        require(value > 0) { "challengeableId must be positive: $value" }
    }
}

/** id attribute of the code input — differs per language (protocol doc §3). */
@JvmInline
value class CodesKey(val value: String) {
    init {
        require(value.isNotBlank()) { "codesKey must not be blank" }
    }
}

/** Channel routing per problem type — both values measured (protocol doc §2). */
enum class ChallengeableType(val wireValue: String, val channelName: String) {
    ALGORITHM("algorithm", "Challenge::AlgorithmChannel"),
    DATABASE("database", "Challenge::DatabaseChannel"),
    ;

    companion object {
        fun from(wireValue: String): ChallengeableType = entries.firstOrNull { it.wireValue == wireValue }
            ?: throw IllegalArgumentException("Unsupported challengeable_type: $wireValue")
    }
}

/**
 * Subscription identifier for a challenge channel. ActionCable keys broadcasts by the
 * exact identifier string, so [asJson] must stay byte-for-byte stable (protocol doc §4).
 */
data class ChannelIdentifier(
    val type: ChallengeableType,
    val lessonId: LessonId,
    val challengeableId: ChallengeableId,
    val language: String,
) {
    fun asJson(): String = buildJsonObject {
        put("channel", type.channelName)
        put("challengeable_type", type.wireValue)
        put("challengeable_id", challengeableId.value)
        put("language", language)
        put("lesson_id", lessonId.value)
    }.toString()

    companion object {
        fun of(
            type: ChallengeableType,
            lessonId: LessonId,
            challengeableId: ChallengeableId,
            language: String,
        ): ChannelIdentifier {
            require(language.isNotBlank()) { "language must not be blank" }
            return ChannelIdentifier(type, lessonId, challengeableId, language)
        }
    }
}
