package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.protocol.SubscriptionProtocol.Step
import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aSqlIdentifier
import com.brokenfinger.tracker.support.fixtures.anAlgorithmIdentifier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SubscriptionProtocolTest {
    private val protocol = SubscriptionProtocol(anAlgorithmIdentifier())
    private val capture = FixtureLoader.rawFrames("algorithm-pass.jsonl")

    @Test
    fun `welcome triggers the subscribe command`() {
        val step = protocol.next(capture[0]).shouldBeInstanceOf<Step.Send>()

        step.frameText shouldBe CableCommand.subscribe(anAlgorithmIdentifier())
    }

    /**
     * Emitted, not ignored (#94). The ping is the only traffic an idle channel produces, so
     * it is what the silence deadline has to measure; ignoring it here left the deadline
     * timing the gap between gradings and reconnecting on every idle channel. The subscriber
     * filters it out downstream, after the timeout has seen it.
     */
    @Test
    fun `ping surfaces as a heartbeat, preserving the raw text`() {
        val step = protocol.next(capture[2]).shouldBeInstanceOf<Step.Emit>()

        step.event.shouldBeInstanceOf<CableEvent.Heartbeat>().rawText shouldBe capture[2]
    }

    @Test
    fun `confirm_subscription emits a confirmation preserving the raw text`() {
        val step = protocol.next(capture[1]).shouldBeInstanceOf<Step.Emit>()

        step.event.shouldBeInstanceOf<CableEvent.SubscriptionConfirmed>().rawText shouldBe capture[1]
    }

    // No measured reject capture exists — we were never rejected (dev rules §6.2 synthetic case).
    @Test
    fun `reject_subscription fails without leaking anything but the identifier`() {
        val step = protocol.next("""{"identifier":"{}","type":"reject_subscription"}""")
            .shouldBeInstanceOf<Step.Fail>()

        step.reason shouldNotContain "_session_production"
    }

    @Test
    fun `broadcasts emit parsed messages preserving the raw text`() {
        val step = protocol.next(capture[3]).shouldBeInstanceOf<Step.Emit>()
        val event = step.event.shouldBeInstanceOf<CableEvent.MessageReceived>()

        event.message.shouldBeInstanceOf<SubmitMessage.Start>()
        event.rawText shouldBe capture[3]
    }

    // Dev rules §2.3 — unknown frames are preserved, never silently dropped.
    @Test
    fun `unknown frame types surface as unhandled events`() {
        val step = protocol.next("""{"type":"pong"}""").shouldBeInstanceOf<Step.Emit>()
        val event = step.event.shouldBeInstanceOf<CableEvent.Unhandled>()

        event.frame.shouldBeInstanceOf<ActionCableFrame.Unknown>()
    }

    // Synthetic — malformed text cannot have a measured capture (dev rules §6.2).
    @Test
    fun `malformed text surfaces as an unhandled event preserving the raw text`() {
        val step = protocol.next("{oops").shouldBeInstanceOf<Step.Emit>()
        val event = step.event.shouldBeInstanceOf<CableEvent.Unhandled>()

        event.rawText shouldBe "{oops"
    }

    // SQL streams end at result_lesson_challenge without finish (protocol doc §6).
    @Test
    fun `sql capture routes every broadcast without requiring finish`() {
        val sqlProtocol = SubscriptionProtocol(aSqlIdentifier())
        val lines = FixtureLoader.rawFrames("sql-pass.jsonl")

        val messages = lines.map(sqlProtocol::next)
            .filterIsInstance<Step.Emit>()
            .map { it.event }
            .filterIsInstance<CableEvent.MessageReceived>()
            .map { it.message }

        messages.last().shouldBeInstanceOf<SubmitMessage.ResultLessonChallenge>()
    }
}
