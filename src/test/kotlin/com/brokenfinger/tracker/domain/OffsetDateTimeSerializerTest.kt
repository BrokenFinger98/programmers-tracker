package com.brokenfinger.tracker.domain

import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The record's timestamp is written in the offset form design §5.2 shows
 * (`2026-08-04T14:23:01+09:00`). The offset itself is data: it says which local day the
 * problem was solved on, which is what the review-date calculator will read later.
 */
class OffsetDateTimeSerializerTest {
    private val json = Json

    @Test
    fun `an offset timestamp encodes to the ISO form of design section 5 point 2`() {
        val encoded = json.encodeToString(OffsetDateTimeSerializer, OffsetDateTime.parse("2026-08-04T14:23:01+09:00"))

        encoded shouldBe "\"2026-08-04T14:23:01+09:00\""
    }

    @Test
    fun `the offset survives the round trip instead of collapsing to UTC`() {
        val original = OffsetDateTime.parse("2026-08-04T14:23:01+09:00")

        val encoded = json.encodeToString(OffsetDateTimeSerializer, original)
        val decoded = json.decodeFromString(OffsetDateTimeSerializer, encoded)

        decoded shouldBe original
        decoded.offset.id shouldBe "+09:00"
    }

    @Test
    fun `the serializer describes itself as a string so schema tooling is not misled`() {
        OffsetDateTimeSerializer.descriptor.serialName shouldBe "java.time.OffsetDateTime"
        OffsetDateTimeSerializer.descriptor.kind shouldBe String.serializer().descriptor.kind
    }
}
