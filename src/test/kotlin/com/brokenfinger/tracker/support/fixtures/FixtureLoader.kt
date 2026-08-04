package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.protocol.message.ActionCableFrame
import com.brokenfinger.tracker.protocol.message.SubmitMessage

/**
 * Loads measured wire captures (.jsonl files) from src/test/resources/fixtures.
 *
 * Every normal-path and verdict-path parser test must go through this loader —
 * hand-written JSON only verifies the protocol we imagined (dev rules §6.2).
 */
object FixtureLoader {
    fun rawFrames(name: String): List<String> = resourceText(name).lineSequence().filter { it.isNotBlank() }.toList()

    fun frames(name: String): List<ActionCableFrame> = rawFrames(name).map(ActionCableFrame::ofReceived)

    fun messages(name: String): List<SubmitMessage> = frames(name)
        .filterIsInstance<ActionCableFrame.Broadcast>()
        .map { SubmitMessage.ofReceived(it.message) }

    private fun resourceText(name: String): String =
        checkNotNull(FixtureLoader::class.java.getResource("/fixtures/$name")) {
            "Missing fixture file: $name"
        }.readText()
}
