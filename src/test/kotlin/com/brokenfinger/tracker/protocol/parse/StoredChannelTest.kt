package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aSqlIdentifier
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Layer test for the inverse of `ChannelIdentifier.asJson` (dev rules §6.1).
 *
 * The normal paths are driven by the identifiers of the measured captures rather than by
 * hand-written JSON (dev rules §6.2): ActionCable keys broadcasts by that exact string
 * (protocol doc §4), so an inverse that does not reproduce it byte-for-byte is worse than
 * none. Only the shapes no capture contains — a torn string, an unknown problem family —
 * are written inline.
 */
class StoredChannelTest {
    @Test
    fun `the identifier of every measured capture round-trips byte-for-byte`() {
        val identifiers = FIXTURES.flatMap(::identifiersOf)

        identifiers.forEach { StoredChannel.ofReceived(it)?.asJson() shouldBe it }
    }

    @Test
    fun `an algorithm identifier reads back as the channel it names`() {
        val identifier = identifiersOf("algorithm-pass.jsonl").first()

        StoredChannel.ofReceived(identifier) shouldBe anAlgorithmIdentifier()
    }

    /** The whole reason this parser exists: nothing else in a stored file says "database". */
    @Test
    fun `a database identifier reads back as the channel it names`() {
        val identifier = identifiersOf("sql-pass.jsonl").first()

        StoredChannel.ofReceived(identifier) shouldBe aSqlIdentifier()
    }

    @Test
    fun `every stored broadcast of one capture names the same channel`() {
        val channels = identifiersOf("algorithm-wrong.jsonl").map { StoredChannel.ofReceived(it) }

        channels.distinct().single().shouldNotBeNull()
    }

    // Failure paths ------------------------------------------------------------------------

    @Test
    fun `an absent identifier resolves to nothing`() {
        StoredChannel.ofReceived(null) shouldBe null
        StoredChannel.ofReceived("  ") shouldBe null
    }

    /** A crash mid-append can tear any line, including the one a channel is read from. */
    @Test
    fun `a torn identifier resolves to nothing rather than throwing`() {
        StoredChannel.ofReceived("""{"channel":"Challenge::Algorith""") shouldBe null
    }

    @Test
    fun `a problem family we have never measured is never guessed at`() {
        StoredChannel.ofReceived(identifierOf(type = "quantum")) shouldBe null
    }

    @Test
    fun `an identifier missing a field we cannot invent resolves to nothing`() {
        StoredChannel.ofReceived("""{"channel":"Challenge::AlgorithmChannel"}""") shouldBe null
    }

    @Test
    fun `an unusable identifier value resolves to nothing`() {
        StoredChannel.ofReceived(identifierOf(challengeableId = 0)) shouldBe null
        StoredChannel.ofReceived(identifierOf(lessonId = -1)) shouldBe null
        StoredChannel.ofReceived(identifierOf(language = "")) shouldBe null
    }

    private fun identifiersOf(fixture: String): List<String> = FixtureLoader.frames(fixture)
        .filterIsInstance<ActionCableFrame.Broadcast>()
        .mapNotNull { it.identifier }

    private fun identifierOf(
        type: String = "algorithm",
        challengeableId: Long = 14643,
        language: String = "java",
        lessonId: Long = 120804,
    ): String = """{"channel":"Challenge::AlgorithmChannel","challengeable_type":"$type",""" +
        """"challengeable_id":$challengeableId,"language":"$language","lesson_id":$lessonId}"""

    private companion object {
        val FIXTURES = listOf(
            "algorithm-pass.jsonl",
            "algorithm-wrong.jsonl",
            "algorithm-timeout.jsonl",
            "algorithm-runtime.jsonl",
            "algorithm-compile.jsonl",
            "algorithm-run-pass.jsonl",
            "algorithm-run-error.jsonl",
            "sql-pass.jsonl",
            "sql-run.jsonl",
        )
    }
}
