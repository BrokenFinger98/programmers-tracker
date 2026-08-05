package com.brokenfinger.tracker.domain

/** Lesson number from the problem URL path (protocol doc §3). */
@JvmInline
value class LessonId(val value: Long) {
    init {
        require(value > 0) { "lessonId must be positive: $value" }
    }
}

/**
 * data-challengeable-id of the problem page — language-independent (protocol doc §3).
 * Distinct type from `CodesKey`: sending the codes key as challengeable_id passes the
 * subscription and testcases but silently fails result finalization (§3 trap). That
 * confusion cost four consecutive failures during reverse engineering, which is why the
 * two stay separate types even though both are just a number and a string underneath.
 */
@JvmInline
value class ChallengeableId(val value: Long) {
    init {
        require(value > 0) { "challengeableId must be positive: $value" }
    }
}

/**
 * The identity of one watched problem channel — which lesson, which challengeable, which
 * problem family, which language ([[decisions/2026-08-05-protocol-dependency-direction]]).
 *
 * Identity, not wire format. Everything above `protocol` keys subscriptions, registries and
 * captures by this; `protocol` turns it into the ActionCable subscription string it has to
 * send. If Programmers changes that string's JSON, only the wire form changes and nothing
 * here does — which is what makes the identity safe to hold in the domain while message
 * types are not.
 */
data class ChannelKey(
    val lessonId: LessonId,
    val challengeableId: ChallengeableId,
    val kind: ProblemKind,
    val language: String,
) {
    companion object {
        /** Strict — these are values we build ourselves, not values received (dev rules §4). */
        fun of(lessonId: LessonId, challengeableId: ChallengeableId, kind: ProblemKind, language: String): ChannelKey {
            require(language.isNotBlank()) { "language must not be blank" }
            return ChannelKey(lessonId, challengeableId, kind, language)
        }
    }
}
