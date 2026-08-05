package com.brokenfinger.tracker.domain

/**
 * Whether a conclusion was observed at all — a dimension separate from [Verdict]
 * (design §3.3). Mixing the two would let a grading we failed to observe dilute the
 * statistics of the gradings we did.
 */
enum class Outcome {
    /** A terminal frame arrived and the message matched a known verdict. */
    JUDGED,

    /** Timeout, disconnect or eviction before a terminal frame — raw frames still kept. */
    INCOMPLETE,

    /** A terminal frame arrived, but the failure message matches nothing measured. */
    UNKNOWN,
}
