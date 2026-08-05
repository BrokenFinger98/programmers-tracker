package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class CableCommandTest {
    private val identifier = anAlgorithmIdentifier()

    @Test
    fun `builds the subscribe command shape of protocol doc §4`() {
        val frame = Json.parseToJsonElement(CableCommand.subscribe(identifier)) as JsonObject

        (frame["command"] as JsonPrimitive).content shouldBe "subscribe"
        frame["identifier"]!!.jsonPrimitive.content shouldBe identifier.asJson()
    }

    // ActionCable keys broadcasts by the exact identifier string; the subscribe
    // identifier must match the server-confirmed capture byte for byte (§4).
    @Test
    fun `subscribe identifier matches the captured confirm_subscription identifier`() {
        val confirmed = FixtureLoader.frames("algorithm-pass.jsonl")[1]
            .shouldBeInstanceOf<ActionCableFrame.ConfirmSubscription>()

        val frame = Json.parseToJsonElement(CableCommand.subscribe(identifier)) as JsonObject

        frame["identifier"]!!.jsonPrimitive.content shouldBe confirmed.identifier
    }
}
