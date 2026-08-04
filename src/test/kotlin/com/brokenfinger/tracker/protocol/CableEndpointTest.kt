package com.brokenfinger.tracker.protocol

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CableEndpointTest {
    // Protocol facts (protocol doc §1, §10) — the defaults must stay these exact values.
    @Test
    fun `defaults to the measured cable endpoint and origin`() {
        val endpoint = CableEndpoint.fromEnvironment { null }

        endpoint.url shouldBe "wss://ws.programmers.co.kr:443/cable"
        endpoint.origin shouldBe "https://school.programmers.co.kr"
    }

    // No hardcoded environments in public distribution (dev rules §9.1).
    @Test
    fun `environment variables override both values`() {
        val env = mapOf(
            "TRACKER_CABLE_URL" to "wss://localhost:9999/cable",
            "TRACKER_CABLE_ORIGIN" to "https://localhost",
        )

        val endpoint = CableEndpoint.fromEnvironment(env::get)

        endpoint.url shouldBe "wss://localhost:9999/cable"
        endpoint.origin shouldBe "https://localhost"
    }
}
