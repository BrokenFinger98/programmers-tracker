package com.brokenfinger.tracker.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The action a grading stream was requested with. Termination depends on it as much as on
 * the problem kind (protocol doc §5–§7), so it is part of the domain rather than a detail
 * of the wire format.
 */
@Serializable
enum class GradingAction {
    // Recorded in the lower case design §5.2 shows, which is also the wire spelling.
    @SerialName("run")
    RUN,

    @SerialName("submit")
    SUBMIT,
    ;

    companion object {
        /**
         * Lenient creation from a received value (development-rules §4) — never throws.
         * Streams we do not grade exist and are broadcast on the same channel
         * (protocol doc §8); an unrecognised action yields null so the frame can still be
         * kept raw instead of losing the whole record to an exception.
         */
        fun ofReceived(raw: String?): GradingAction? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
