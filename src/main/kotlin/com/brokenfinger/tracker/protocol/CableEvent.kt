package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.protocol.message.SubmitMessage

/**
 * What an observed subscription yields. Every event preserves the raw wire text —
 * storing only parse results is forbidden (dev rules §2.4).
 */
sealed interface CableEvent {
    val rawText: String

    data class SubscriptionConfirmed(override val rawText: String) : CableEvent

    data class MessageReceived(override val rawText: String, val identifier: String?, val message: SubmitMessage) :
        CableEvent

    /** Unknown or malformed frames, preserved instead of dropped (dev rules §2.3). */
    data class Unhandled(override val rawText: String, val frame: ActionCableFrame) : CableEvent
}
