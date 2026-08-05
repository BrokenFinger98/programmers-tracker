package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ProblemKind

/**
 * A validated request to observe one problem channel (design §4.1).
 *
 * The identifiers stay plain numbers here on purpose: this is what the web adapter parsed
 * out of a request, before anything has decided it names a real channel. [WatchService]
 * wraps them into a `ChannelKey`, and that construction is where the validation belongs.
 * `codesKey` stays a string for the same reason — its value class is a protocol concern.
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
