package com.brokenfinger.tracker.protocol.message

import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

class ActionCableFrameTest {
    private val frames = FixtureLoader.frames("algorithm-pass.jsonl")

    @Test
    fun `parses welcome frame`() {
        frames[0] shouldBe ActionCableFrame.Welcome
    }

    @Test
    fun `parses ping heartbeat frame`() {
        frames[2] shouldBe ActionCableFrame.Ping
    }

    @Test
    fun `parses confirm_subscription with identifier preserved`() {
        val confirm = frames[1].shouldBeInstanceOf<ActionCableFrame.ConfirmSubscription>()
        confirm.identifier.shouldNotBeNull() shouldContain "Challenge::AlgorithmChannel"
    }

    @Test
    fun `parses broadcast frame with identifier and inner message`() {
        val broadcast = frames[3].shouldBeInstanceOf<ActionCableFrame.Broadcast>()
        broadcast.identifier.shouldNotBeNull() shouldContain "\"lesson_id\":120804"
        broadcast.message["action"] shouldBe JsonPrimitive("submit")
    }

    // No measured reject_subscription capture exists — synthetic inline JSON (test decision C).
    @Test
    fun `parses reject_subscription`() {
        val frame = ActionCableFrame.ofReceived("""{"type":"reject_subscription","identifier":"id"}""")
        frame shouldBe ActionCableFrame.RejectSubscription("id")
    }

    @Test
    fun `preserves unknown frame type instead of dropping it`() {
        val frame = ActionCableFrame.ofReceived("""{"type":"disconnect","reason":"unauthorized"}""")
        val unknown = frame.shouldBeInstanceOf<ActionCableFrame.Unknown>()
        unknown.type shouldBe "disconnect"
        unknown.raw["reason"] shouldBe JsonPrimitive("unauthorized")
    }

    @Test
    fun `preserves malformed text as Malformed`() {
        ActionCableFrame.ofReceived("not json {{{") shouldBe ActionCableFrame.Malformed("not json {{{")
    }

    @Test
    fun `treats non-object json as Malformed`() {
        ActionCableFrame.ofReceived("[1,2,3]") shouldBe ActionCableFrame.Malformed("[1,2,3]")
    }

    @Test
    fun `keeps broadcast without identifier null instead of substituting a default`() {
        val frame = ActionCableFrame.ofReceived("""{"message":{"action":"submit","type":"finish"}}""")
        frame.shouldBeInstanceOf<ActionCableFrame.Broadcast>().identifier shouldBe null
    }

    @Test
    fun `treats frame with neither type nor message as Unknown`() {
        ActionCableFrame.ofReceived("{}") shouldBe ActionCableFrame.Unknown(null, buildJsonObject {})
    }
}
