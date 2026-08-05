package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingFrameFacts

/**
 * Reads stored wire frames into the terms the capture path works in — the port that keeps
 * message knowledge inside `protocol`
 * ([[decisions/2026-08-05-protocol-dependency-direction]] decision 2).
 *
 * It is a port for the reason every outbound dependency is one (dev rules §1): the
 * implementation belongs to `protocol/parse`, and naming it here would put the wire format
 * back on the verdict path that this boundary exists to protect. The live socket needs no
 * port — its frames arrive already routed and reach [ChannelCapture] as [ObservedFrame].
 *
 * Lenient by direction (dev rules §4): these are bytes Programmers produced and a crash may
 * have torn, so a frame that cannot be read yields null and never throws. One unreadable
 * line must cost that line and nothing behind it.
 */
interface FrameReader {
    /** What one stored frame said, or null when no frame could be read from [rawText]. */
    fun factsOf(rawText: String): GradingFrameFacts?

    /**
     * The channel this frame's envelope names. The envelope is the only place the problem
     * family and the language survive a crash — the implementation records the measurement.
     */
    fun channelOf(rawText: String): ChannelKey?
}
