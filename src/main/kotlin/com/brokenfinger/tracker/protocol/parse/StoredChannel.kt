package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.domain.ChallengeableId
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.LessonId
import com.brokenfinger.tracker.protocol.ChallengeableType
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * The inverse of [ChannelIdentifier.asJson] — a stored ActionCable envelope identifier read
 * back into the channel it names (protocol doc §4).
 *
 * It exists for crash recovery. Replaying a stored raw session needs the problem family (a
 * database submit never receives a `finish` frame, protocol doc §6) and the language, and
 * **the envelope identifier is the only place either can be found**: measured against all
 * nine captures in `src/test/resources/fixtures`, an algorithm *submit*'s inner messages
 * carry no `challengeable_type` at all, and `language` appears only on
 * `result_lesson_challenge` — the one frame a crash mid-grading is most likely to have
 * happened before. Deriving the family from the payload would therefore fail in exactly the
 * case recovery exists for.
 *
 * Lenient by direction (dev rules §5): these are bytes Programmers produced and a crash may
 * have torn, so an unreadable identifier yields null with a warning and never throws — one
 * unreadable file must not abandon every session queued behind it. An unrecognised problem
 * family also yields null rather than the nearest neighbour, because substituting a default
 * for a failed identifier extraction is what files wrong data silently.
 */
object StoredChannel {
    private val logger = LoggerFactory.getLogger(StoredChannel::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun ofReceived(identifier: String?): ChannelIdentifier? {
        val fields = fieldsOf(identifier) ?: return declined("not a JSON object")
        return runCatching { channelOf(fields) }.getOrElse { declined(it.message) }
    }

    private fun fieldsOf(identifier: String?): JsonObject? {
        val text = identifier?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }

    // Every value is demanded rather than defaulted: the constructors reject a missing one,
    // which is the throw this method turns into a null the caller can act on.
    private fun channelOf(fields: JsonObject): ChannelIdentifier = ChannelIdentifier.from(
        ChannelKey.of(
            LessonId(fields.long(LESSON_ID) ?: ABSENT),
            ChallengeableId(fields.long(CHALLENGEABLE_ID) ?: ABSENT),
            kindOf(fields),
            fields.string(LANGUAGE).orEmpty(),
        ),
    )

    private fun kindOf(fields: JsonObject) =
        GradingMessageMapper.problemKindOf(ChallengeableType.from(fields.string(CHALLENGEABLE_TYPE).orEmpty()))

    // The identifier itself is never logged — it carries which problem a learner was solving
    // (dev rules §7). The reason alone is what tells us the protocol moved.
    private fun declined(reason: String?): ChannelIdentifier? {
        logger.warn("A stored channel identifier was left unresolved rather than guessed at: {}", reason)
        return null
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    /** Fails every `require` in the value classes, so an absent field reads as a refusal. */
    private const val ABSENT = -1L

    private const val CHALLENGEABLE_TYPE = "challengeable_type"
    private const val CHALLENGEABLE_ID = "challengeable_id"
    private const val LESSON_ID = "lesson_id"
    private const val LANGUAGE = "language"
}
