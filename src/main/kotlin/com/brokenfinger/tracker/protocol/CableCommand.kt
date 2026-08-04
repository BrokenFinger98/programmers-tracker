package com.brokenfinger.tracker.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Outgoing ActionCable command frames (protocol doc §4). The identifier is embedded as
 * the exact [ChannelIdentifier.asJson] string — ActionCable keys broadcasts by it.
 */
object CableCommand {
    fun subscribe(identifier: ChannelIdentifier): String = buildJsonObject {
        put("command", "subscribe")
        put("identifier", identifier.asJson())
    }.toString()
}
