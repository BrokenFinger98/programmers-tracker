package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.application.FrameReader
import com.brokenfinger.tracker.application.ObservedFrame
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.protocol.message.SubmitMessage

/**
 * The frame-level crossing out of the protocol (dev rules §2.1): a live cable event or a
 * stored line goes in, and what comes out names no wire type.
 *
 * Two entry points, because the two capture paths hold different things. The live socket has
 * already parsed the envelope on its way to a [CableEvent], so re-reading its text would
 * parse every frame twice; a raw session holds bytes and nothing else. Both end at the same
 * [GradingMessageMapper] call, which is what makes a reconciled grading settle to the verdict
 * the live path would have produced.
 */
object ObservedFrames : FrameReader {
    /** A frame the live subscription has already routed (protocol doc §4). */
    fun of(event: CableEvent): ObservedFrame = ObservedFrame(event.rawText, liveFactsOf(event))

    override fun factsOf(rawText: String): GradingFrameFacts? {
        val broadcast = broadcastOf(rawText) ?: return null
        return GradingMessageMapper.factsOf(SubmitMessage.ofReceived(broadcast.message))
    }

    /** Only a broadcast carries an identifier we can resolve — see [StoredChannel] for why. */
    override fun channelOf(rawText: String): ChannelKey? {
        // Asked only of frames that have one: a line carrying no envelope is not an identifier
        // we failed to resolve, and reporting it as one would cry wolf about a protocol change.
        val identifier = broadcastOf(rawText)?.identifier ?: return null
        return StoredChannel.ofReceived(identifier)?.key
    }

    // Confirmations and the frames nothing recognised said nothing about a grading. They are
    // still kept by the caller (dev rules §2.3); what they contribute is nothing.
    private fun liveFactsOf(event: CableEvent): GradingFrameFacts? {
        val message = (event as? CableEvent.MessageReceived)?.message ?: return null
        return GradingMessageMapper.factsOf(message)
    }

    private fun broadcastOf(rawText: String): ActionCableFrame.Broadcast? =
        ActionCableFrame.ofReceived(rawText) as? ActionCableFrame.Broadcast
}
