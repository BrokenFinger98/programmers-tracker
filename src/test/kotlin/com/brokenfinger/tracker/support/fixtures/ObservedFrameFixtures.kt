package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.FrameReader
import com.brokenfinger.tracker.application.ObservedFrame
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.SubscriptionProtocol
import com.brokenfinger.tracker.protocol.parse.ObservedFrames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Object mothers (dev rules §6.4) for what a subscription hands the capture. Wire text becomes
// a frame through the production routing and the production reader, so a capture test consumes
// exactly what the socket loop emits rather than a hand-made approximation of it.

/** A measured capture (dev rules §6.2) as the stream of frames it would have produced. */
fun observedFrames(fixture: String, channel: ChannelKey = anAlgorithmChannel()): List<ObservedFrame> =
    FixtureLoader.rawFrames(fixture).mapNotNull { anObservedFrameOrNull(it, channel) }

/** One frame's wire text as the capture would receive it. Welcome and ping emit none. */
fun anObservedFrame(rawText: String, channel: ChannelKey = anAlgorithmChannel()): ObservedFrame =
    checkNotNull(anObservedFrameOrNull(rawText, channel)) { "this frame emits no event" }

/** A broadcast wrapper around [message], for the shapes no capture contains. */
fun aBroadcastFrame(message: String, channel: ChannelKey = anAlgorithmChannel()): String = buildJsonObject {
    put("identifier", ChannelIdentifier.from(channel).asJson())
    put("message", Json.parseToJsonElement(message))
}.toString()

/** The production reader — a replay test that doubled it would prove nothing about the bytes. */
fun aFrameReader(): FrameReader = ObservedFrames

private fun anObservedFrameOrNull(rawText: String, channel: ChannelKey): ObservedFrame? =
    emittedBy(rawText, channel)?.let(ObservedFrames::of)

private fun emittedBy(rawText: String, channel: ChannelKey): CableEvent? =
    (SubscriptionProtocol(ChannelIdentifier.from(channel)).next(rawText) as? SubscriptionProtocol.Step.Emit)?.event
