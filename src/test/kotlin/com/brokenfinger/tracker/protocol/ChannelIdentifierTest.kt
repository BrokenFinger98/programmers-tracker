package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ChannelIdentifierTest {
    private val algorithmIdentifier =
        ChannelIdentifier.of(ChallengeableType.ALGORITHM, LessonId(120804), ChallengeableId(14643), "java")

    // ActionCable keys broadcasts by the exact identifier string (protocol doc §4).
    @Test
    fun `builds the subscription identifier byte for byte`() {
        algorithmIdentifier.asJson() shouldBe
            "{\"channel\":\"Challenge::AlgorithmChannel\",\"challengeable_type\":\"algorithm\"," +
            "\"challengeable_id\":14643,\"language\":\"java\",\"lesson_id\":120804}"
    }

    @Test
    fun `matches the captured algorithm confirm_subscription identifier`() {
        algorithmIdentifier.asJson() shouldBe capturedIdentifier("algorithm-pass.jsonl")
    }

    @Test
    fun `routes sql problems to the database channel and matches capture`() {
        val identifier =
            ChannelIdentifier.of(ChallengeableType.DATABASE, LessonId(131528), ChallengeableId(2778), "mysql")
        identifier.asJson() shouldBe capturedIdentifier("sql-pass.jsonl")
    }

    @Test
    fun `reads measured challengeable_type values`() {
        ChallengeableType.from("algorithm") shouldBe ChallengeableType.ALGORITHM
        ChallengeableType.from("database") shouldBe ChallengeableType.DATABASE
    }

    // Strict validation — we create subscription values ourselves (dev rules §4).
    @Test
    fun `rejects unsupported challengeable_type`() {
        shouldThrow<IllegalArgumentException> { ChallengeableType.from("quiz") }
    }

    @Test
    fun `rejects non-positive lesson id`() {
        shouldThrow<IllegalArgumentException> { LessonId(0) }
    }

    @Test
    fun `rejects non-positive challengeable id`() {
        shouldThrow<IllegalArgumentException> { ChallengeableId(-1) }
    }

    @Test
    fun `rejects blank codes key`() {
        shouldThrow<IllegalArgumentException> { CodesKey(" ") }
    }

    @Test
    fun `rejects blank language`() {
        shouldThrow<IllegalArgumentException> {
            ChannelIdentifier.of(ChallengeableType.ALGORITHM, LessonId(120804), ChallengeableId(14643), " ")
        }
    }

    // The §3 trap: sending the codes key as challengeable_id silently breaks result finalization.
    @Test
    fun `challengeable id and codes key are distinct types`() {
        (ChallengeableId(49598) as Any) shouldNotBe (CodesKey("49598") as Any)
    }

    private fun capturedIdentifier(fixture: String): String = FixtureLoader.frames(fixture)[1]
        .shouldBeInstanceOf<ActionCableFrame.ConfirmSubscription>()
        .identifier!!
}
