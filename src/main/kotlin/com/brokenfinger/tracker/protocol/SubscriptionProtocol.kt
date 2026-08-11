package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.protocol.message.SubmitMessage

/**
 * Pure state routing for one channel subscription (protocol doc §4): welcome triggers
 * the subscribe command, rejection fails, and everything else — pings included — surfaces
 * as a [CableEvent]. No I/O — the socket loop executes the returned [Step].
 */
class SubscriptionProtocol(private val identifier: ChannelIdentifier) {
    fun next(rawText: String): Step = when (val frame = ActionCableFrame.ofReceived(rawText)) {
        ActionCableFrame.Welcome -> Step.Send(CableCommand.subscribe(identifier))
        // Emitted, not ignored: the ping is the only traffic an idle channel produces, so it
        // is what tells silence apart from a dead socket (#94). The subscriber drops it
        // after the deadline has seen it.
        ActionCableFrame.Ping -> Step.Emit(CableEvent.Heartbeat(rawText))
        is ActionCableFrame.ConfirmSubscription -> Step.Emit(CableEvent.SubscriptionConfirmed(rawText))
        // The identifier is deliberately not in the reason. The subscriber's log line already
        // names the lesson, and StoredChannel states the policy for this value; an exception
        // message is the one place it was contradicted (2026-08-07 review, MINOR).
        is ActionCableFrame.RejectSubscription -> Step.Fail(REJECTED)
        is ActionCableFrame.Broadcast -> broadcast(rawText, frame)
        is ActionCableFrame.Unknown -> Step.Emit(CableEvent.Unhandled(rawText, frame))
        is ActionCableFrame.Malformed -> Step.Emit(CableEvent.Unhandled(rawText, frame))
    }

    private fun broadcast(rawText: String, frame: ActionCableFrame.Broadcast): Step =
        Step.Emit(CableEvent.MessageReceived(rawText, frame.identifier, SubmitMessage.ofReceived(frame.message)))

    private companion object {
        /** Written for the person reading a badge tooltip, not for a stack trace. */
        const val REJECTED = "the judge refused the subscription — the session cookie is the usual cause"
    }

    sealed interface Step {
        data class Send(val frameText: String) : Step

        data class Emit(val event: CableEvent) : Step

        data class Fail(val reason: String) : Step
    }
}
