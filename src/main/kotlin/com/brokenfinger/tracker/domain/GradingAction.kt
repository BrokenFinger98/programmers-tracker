package com.brokenfinger.tracker.domain

/**
 * The action a grading stream was requested with. Termination depends on it as much as on
 * the problem kind (protocol doc §5–§7), so it is part of the domain rather than a detail
 * of the wire format.
 */
enum class GradingAction {
    RUN,
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
