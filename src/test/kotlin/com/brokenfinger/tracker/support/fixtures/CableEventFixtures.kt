package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.SubscriptionProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Object mothers (dev rules §6.4) for the events a subscription yields. Frames become events
// through the production routing, so a capture test consumes exactly what the socket loop
// emits rather than a hand-made approximation of it.

/** A measured capture (dev rules §6.2) as the stream of events it would have produced. */
fun cableEvents(fixture: String, channel: ChannelKey = anAlgorithmChannel()): List<CableEvent> =
    FixtureLoader.rawFrames(fixture).mapNotNull { emittedBy(it, channel) }

/** One frame as an event. Fails for the frames that emit none — welcome and ping. */
fun aCableEvent(rawText: String, channel: ChannelKey = anAlgorithmChannel()): CableEvent =
    checkNotNull(emittedBy(rawText, channel)) { "this frame emits no event" }

/** A broadcast wrapper around [message], for the shapes no capture contains. */
fun aBroadcastFrame(message: String, channel: ChannelKey = anAlgorithmChannel()): String = buildJsonObject {
    put("identifier", ChannelIdentifier.from(channel).asJson())
    put("message", Json.parseToJsonElement(message))
}.toString()

private fun emittedBy(rawText: String, channel: ChannelKey): CableEvent? =
    (SubscriptionProtocol(ChannelIdentifier.from(channel)).next(rawText) as? SubscriptionProtocol.Step.Emit)?.event
