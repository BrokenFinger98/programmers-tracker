package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.GradingSession
import com.brokenfinger.tracker.application.GradingSessionAssembler
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.protocol.parse.GradingMessageMapper

// Object mothers (dev rules §6.4) that drive the assembler from measured captures
// (dev rules §6.2). Every helper here goes through FixtureLoader — a hand-written stream
// would only prove the protocol we imagined.

fun anAssembler(channel: ChannelIdentifier = anAlgorithmIdentifier(), boundErrorText: String? = null) =
    GradingSessionAssembler.of(channel, boundErrorText)

/** Feeds a whole measured capture into a fresh assembler and settles it. */
fun anAssembledSession(
    fixture: String,
    channel: ChannelIdentifier = anAlgorithmIdentifier(),
    boundErrorText: String? = null,
): GradingSession = aSessionOf(FixtureLoader.messages(fixture), channel, boundErrorText)

/** Same, but for a stream a test has reordered, truncated or amended. */
fun aSessionOf(
    messages: List<SubmitMessage>,
    channel: ChannelIdentifier = anAlgorithmIdentifier(),
    boundErrorText: String? = null,
): GradingSession = anAssembler(channel, boundErrorText).apply { messages.forEach(::accept) }.settle()

/**
 * The nth measured run-path error text, unescaped. Index 0 is the compiler diagnostic and
 * index 1 the runtime stack trace (protocol doc §7) — the two shapes the submit path cannot
 * tell apart on its own.
 */
fun aRunErrorText(index: Int): String {
    val errors = FixtureLoader.messages("algorithm-run-error.jsonl").filterIsInstance<SubmitMessage.Error>()
    return checkNotNull(GradingMessageMapper.errorTextOf(errors[index])) { "run error $index carried no msg" }
}
