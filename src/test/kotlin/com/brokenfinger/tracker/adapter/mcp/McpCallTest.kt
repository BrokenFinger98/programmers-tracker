package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.support.fixtures.aLegacyBody
import com.brokenfinger.tracker.support.fixtures.aModernBody
import com.brokenfinger.tracker.support.fixtures.aToolCallParams
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class McpCallTest {
    @Test
    fun `reads a handshake-era request`() {
        val call = McpCall.from(aLegacyBody("tools/list"))

        call.method shouldBe "tools/list"
        call.isModern().shouldBeFalse()
        call.isNotification().shouldBeFalse()
        call.declaredVersion.shouldBeNull()
    }

    /** The era decision, and the only thing that makes it: a per-request protocol version. */
    @Test
    fun `a per-request protocol version is what makes a request modern`() {
        val call = McpCall.from(aModernBody("tools/list"))

        call.isModern().shouldBeTrue()
        call.declaredVersion shouldBe McpProtocol.MODERN
        call.declaresClientCapabilities.shouldBeTrue()
    }

    @Test
    fun `notices a modern request that omits the required client capabilities`() {
        McpCall.from(aModernBody("tools/list", withCapabilities = false)).declaresClientCapabilities.shouldBeFalse()
    }

    @Test
    fun `a request with no id is a notification`() {
        McpCall.from(aLegacyBody("notifications/initialized", id = null)).isNotification().shouldBeTrue()
    }

    /**
     * The spec forbids an explicit null id. Treating it as the absence it looks like is
     * better than answering with a null id, which is neither a response nor a notification.
     */
    @Test
    fun `an explicit null id is treated as no id at all`() {
        McpCall.from("""{"jsonrpc":"2.0","id":null,"method":"tools/list"}""").isNotification().shouldBeTrue()
    }

    @Test
    fun `reads the tool name and arguments of a call`() {
        val params = aToolCallParams("stats", buildJsonObject { put("groupBy", "verdict") })

        val call = McpCall.from(aLegacyBody("tools/call", params))

        call.toolName() shouldBe "stats"
        call.stringArgument("groupBy") shouldBe "verdict"
    }

    @Test
    fun `a call with no arguments member has empty arguments rather than failing`() {
        val call = McpCall.from(aLegacyBody("tools/call", buildJsonObject { put("name", "stats") }))

        call.arguments().shouldBeEmpty()
        call.stringArgument("groupBy").shouldBeNull()
    }

    @Test
    fun `a request with no params has empty params`() {
        McpCall.from("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").params.shouldBeEmpty()
    }

    @Test
    fun `refuses a body that is not JSON`() {
        val thrown = shouldThrow<McpFailure> { McpCall.from("{ not json") }

        thrown.code shouldBe McpErrors.PARSE
        thrown.status shouldBe 400
    }

    @Test
    fun `refuses an empty body`() {
        shouldThrow<McpFailure> { McpCall.from("") }.code shouldBe McpErrors.PARSE
    }

    @Test
    fun `refuses a JSON array, which is not a JSON-RPC object`() {
        shouldThrow<McpFailure> { McpCall.from("""[{"jsonrpc":"2.0"}]""") }.code shouldBe McpErrors.PARSE
    }

    @Test
    fun `refuses a request with no method`() {
        val thrown = shouldThrow<McpFailure> { McpCall.from("""{"jsonrpc":"2.0","id":1}""") }

        thrown.code shouldBe McpErrors.INVALID_REQUEST
        thrown.status shouldBe 400
    }

    /** A member of the wrong JSON type is malformed input, never an internal error. */
    @Test
    fun `refuses a method that is not a string instead of failing internally`() {
        shouldThrow<McpFailure> { McpCall.from("""{"jsonrpc":"2.0","id":1,"method":{"nested":true}}""") }
            .code shouldBe McpErrors.INVALID_REQUEST
    }

    @Test
    fun `reads a tool name of the wrong type as absent rather than throwing`() {
        val call = McpCall.from("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":{"a":1}}}""")

        call.toolName().shouldBeNull()
    }

    @Test
    fun `reads params that are not an object as empty params`() {
        McpCall.from("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":[1,2]}""").params.shouldBeEmpty()
    }

    @Test
    fun `keeps a member it does not recognise instead of refusing the request`() {
        val call = McpCall.from("""{"jsonrpc":"2.0","id":1,"method":"tools/list","somethingNew":42}""")

        call.method shouldBe "tools/list"
    }
}
