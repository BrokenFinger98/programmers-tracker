package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.protocol.parse.GradingMessageMapper

/**
 * Loads measured wire captures (.jsonl files) from src/test/resources/fixtures.
 *
 * Every normal-path and verdict-path parser test must go through this loader —
 * hand-written JSON only verifies the protocol we imagined (dev rules §6.2).
 *
 * The crossing out of the protocol happens here, in test support, for the same reason it
 * happens in `protocol/parse` in production: an `application` test that named a message type
 * would re-open the coupling the boundary closed
 * ([[decisions/2026-08-05-protocol-dependency-direction]] decision 2).
 */
object FixtureLoader {
    fun rawFrames(name: String): List<String> = resourceText(name).lineSequence().filter { it.isNotBlank() }.toList()

    fun frames(name: String): List<ActionCableFrame> = rawFrames(name).map(ActionCableFrame::ofReceived)

    /** The lines a live capture would have appended — the broadcasts, welcome and ping excluded. */
    fun broadcastLines(name: String): List<String> =
        rawFrames(name).filter { ActionCableFrame.ofReceived(it) is ActionCableFrame.Broadcast }

    fun messages(name: String): List<SubmitMessage> = frames(name)
        .filterIsInstance<ActionCableFrame.Broadcast>()
        .map { SubmitMessage.ofReceived(it.message) }

    /** A capture as the facts the assembler is fed — what `application` sees of it. */
    fun facts(name: String): List<GradingFrameFacts> = messages(name).map(GradingMessageMapper::factsOf)

    private fun resourceText(name: String): String =
        checkNotNull(FixtureLoader::class.java.getResource("/fixtures/$name")) {
            "Missing fixture file: $name"
        }.readText()
}
