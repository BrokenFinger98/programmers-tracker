package com.brokenfinger.tracker.domain

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Our own identity for one captured grading.
 *
 * Programmers issues no submission id (protocol doc §11), and `(lessonId, action, attempt)`
 * does not identify a `run` either, because runs keep the previous submit's attempt number
 * (design §5.1). Without a key of our own the writer cannot drop the duplicate that a
 * re-subscribe after a reconnect produces
 * ([[decisions/2026-08-05-write-serialization]] decision 6).
 *
 * The key is deliberately a *content* digest: it must survive a process restart, so nothing
 * ambient — no clock, no random, no counter — may enter the derivation.
 */
@Serializable
@JvmInline
value class CaptureKey(val value: String) {
    init {
        require(value.isNotBlank()) { "captureKey must not be blank" }
    }

    companion object {
        /** Half of a SHA-256. 64 bits is far past the collision horizon of one human's records. */
        private const val WIDTH = 16

        /**
         * Unit separator. JSON forbids raw control characters inside strings, so a frame can
         * never contain one and the three parts can never be shuffled into each other.
         */
        private const val SEPARATOR = '\u001F'

        /**
         * Derives the key from **every frame of the grading**, bound to the lesson and the
         * action that produced it. Deterministic: identical inputs give an identical key in
         * any process, on any host, at any time.
         *
         * **It used to be the terminal frame alone, and that made the key a constant.** An
         * algorithm submit terminates on `finish`, and a measured `finish` is
         * `{"action":"submit","type":"finish"}` beside the channel identifier — nothing that
         * varies between gradings. So every submit of a problem after the first collided with
         * it and was dropped as a replay. Measured 2026-08-11 on lesson 181947: a passing
         * submit derived the key of a WRONG submit from five days earlier, byte-identical
         * frames (#149).
         *
         * The whole session distinguishes them because the testcase frames carry `run_time`
         * and `memory_size`, and it stays stable under replay because the raw log keeps every
         * frame verbatim. Frames are trimmed, because the live path holds the text as it
         * arrived and the replay path reads it back without its line break.
         */
        fun of(lessonId: Long, action: GradingAction, frames: List<String>): CaptureKey {
            val basis = frames.map { it.trim() }.filter { it.isNotEmpty() }
            require(basis.isNotEmpty()) { "capture key cannot be derived from a grading with no frames" }
            return CaptureKey(
                digestOf("$lessonId$SEPARATOR${action.name}$SEPARATOR${basis.joinToString(SEPARATOR.toString())}"),
            )
        }

        /** Lenient read-back from storage (dev rules §4) — a torn line must not throw. */
        fun ofReceived(raw: String?): CaptureKey? = raw?.takeIf { it.isNotBlank() }?.let(::CaptureKey)

        private fun digestOf(canonical: String): String = HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
            .take(WIDTH)
    }
}
