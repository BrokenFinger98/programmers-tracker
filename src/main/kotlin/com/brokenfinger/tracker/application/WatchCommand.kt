package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ProblemKind

/**
 * A validated request to observe one problem channel (design §4.1).
 *
 * The identifiers stay plain numbers here on purpose: their value classes (`LessonId`,
 * `ChallengeableId`, `CodesKey`) live in the `protocol` package because they encode
 * protocol facts, and `application` must not depend on `protocol`. The web adapter has
 * already guaranteed what this layer needs — both ids are positive, both strings are
 * non-blank, and the kind is a known one.
 */
data class WatchCommand(
    val lessonId: Long,
    val challengeableId: Long,
    val kind: ProblemKind,
    val language: String,
    val codesKey: String,
)

/**
 * `/watch` is idempotent: the extension re-posts every 30 s per open tab, so a repeat for a
 * channel we already hold must refresh recency and do nothing else. Re-subscribing on every
 * heartbeat would duplicate records (design §4.1).
 */
enum class WatchOutcome {
    STARTED,
    REFRESHED,
}
