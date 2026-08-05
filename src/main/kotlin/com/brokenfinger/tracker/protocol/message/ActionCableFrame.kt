package com.brokenfinger.tracker.protocol.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * ActionCable envelope frame as received off the wire (protocol doc §4).
 *
 * Parsing is lenient and never throws: unrecognized frame types are preserved as
 * [Unknown] and unparseable text as [Malformed] — silently dropping either is the
 * only way to miss a protocol change (dev rules §2.3).
 */
sealed interface ActionCableFrame {
    data object Welcome : ActionCableFrame

    /** Heartbeat arriving every 3 seconds; carries no payload we use (protocol doc §4). */
    data object Ping : ActionCableFrame

    data class ConfirmSubscription(val identifier: String?) : ActionCableFrame

    data class RejectSubscription(val identifier: String?) : ActionCableFrame

    /** Server broadcast: `{"identifier":"…","message":{…}}` (protocol doc §4). */
    data class Broadcast(val identifier: String?, val message: JsonObject) : ActionCableFrame

    data class Unknown(val type: String?, val raw: JsonObject) : ActionCableFrame

    data class Malformed(val rawText: String) : ActionCableFrame

    companion object {
        private val logger = LoggerFactory.getLogger(ActionCableFrame::class.java)
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        fun ofReceived(rawText: String): ActionCableFrame {
            val frame = runCatching { json.parseToJsonElement(rawText) }.getOrNull() as? JsonObject
                ?: return malformed(rawText)
            return ofFrame(frame)
        }

        private fun ofFrame(frame: JsonObject): ActionCableFrame = when (val type = frame.stringField("type")) {
            "welcome" -> Welcome
            "ping" -> Ping
            "confirm_subscription" -> ConfirmSubscription(frame.stringField("identifier"))
            "reject_subscription" -> RejectSubscription(frame.stringField("identifier"))
            null -> broadcastOrUnknown(frame)
            else -> unknown(type, frame)
        }

        private fun broadcastOrUnknown(frame: JsonObject): ActionCableFrame {
            val message = frame["message"] as? JsonObject ?: return unknown(null, frame)
            return Broadcast(frame.stringField("identifier"), message)
        }

        private fun unknown(type: String?, frame: JsonObject): Unknown {
            logger.warn("Unknown ActionCable frame type '{}' preserved as-is", type)
            return Unknown(type, frame)
        }

        private fun malformed(rawText: String): Malformed {
            logger.warn("Malformed ActionCable frame received (length={})", rawText.length)
            return Malformed(rawText)
        }

        private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    }
}
