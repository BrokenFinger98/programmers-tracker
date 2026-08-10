package com.brokenfinger.tracker.application

/**
 * A validated request to observe one problem channel (design §4.1).
 *
 * **Two fields, because two is all the server cannot work out for itself** (#114).
 * `challengeable_id` and `challengeable_type` are properties of the problem rather than of
 * the caller — protocol §3 measured both as fixed per lesson and independent of language —
 * so [ProblemIdentityResolver] reads them off the page instead of making every caller paste
 * them out of DevTools. `codesKey` was asked for and never used by anything.
 *
 * The lesson stays a plain number here: this is what the web adapter parsed out of a
 * request, before anything has decided it names a real channel. [WatchService] wraps it
 * into a `ChannelKey`, and that construction is where the validation belongs.
 */
data class WatchCommand(val lessonId: Long, val language: String)

/**
 * `/watch` is idempotent: the extension re-posts every 30 s per open tab, so a repeat for a
 * channel we already hold must refresh recency and do nothing else. Re-subscribing on every
 * heartbeat would duplicate records (design §4.1).
 */
enum class WatchOutcome {
    STARTED,
    REFRESHED,
}
