package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.ProblemKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** id attribute of the code input — differs per language (protocol doc §3). */
@JvmInline
value class CodesKey(val value: String) {
    init {
        require(value.isNotBlank()) { "codesKey must not be blank" }
    }
}

/**
 * Channel routing per problem type — both values measured (protocol doc §2). Wire mapping,
 * so it stays here: [ProblemKind] is what the rest of the codebase reasons about.
 */
enum class ChallengeableType(val wireValue: String, val channelName: String) {
    ALGORITHM("algorithm", "Challenge::AlgorithmChannel"),
    DATABASE("database", "Challenge::DatabaseChannel"),
    ;

    companion object {
        fun from(wireValue: String): ChallengeableType = entries.firstOrNull { it.wireValue == wireValue }
            ?: throw IllegalArgumentException("Unsupported challengeable_type: $wireValue")

        fun from(kind: ProblemKind): ChallengeableType = when (kind) {
            ProblemKind.ALGORITHM -> ALGORITHM
            ProblemKind.DATABASE -> DATABASE
        }
    }
}

/**
 * The wire form of a [ChannelKey] — the subscription identifier ActionCable expects.
 *
 * The key is the identity and this is how it is spelled on the socket
 * ([[decisions/2026-08-05-protocol-dependency-direction]]). ActionCable keys broadcasts by
 * the exact identifier string, so [asJson] must stay byte-for-byte stable (protocol doc §4).
 */
data class ChannelIdentifier(val key: ChannelKey) {
    private val type: ChallengeableType get() = ChallengeableType.from(key.kind)

    fun asJson(): String = buildJsonObject {
        put("channel", type.channelName)
        put("challengeable_type", type.wireValue)
        put("challengeable_id", key.challengeableId.value)
        put("language", key.language)
        put("lesson_id", key.lessonId.value)
    }.toString()

    companion object {
        fun from(key: ChannelKey): ChannelIdentifier = ChannelIdentifier(key)
    }
}
