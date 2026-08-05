package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.GradingSession
import com.brokenfinger.tracker.application.GradingSessionAssembler
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.protocol.parse.GradingMessageMapper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Object mothers (dev rules §6.4) that drive the assembler from measured captures
// (dev rules §6.2). Every helper here goes through FixtureLoader — a hand-written stream
// would only prove the protocol we imagined.

fun anAssembler(channel: ChannelKey = anAlgorithmChannel(), boundErrorText: String? = null) =
    GradingSessionAssembler.of(channel, boundErrorText)

/** Feeds a whole measured capture into a fresh assembler and settles it. */
fun anAssembledSession(
    fixture: String,
    channel: ChannelKey = anAlgorithmChannel(),
    boundErrorText: String? = null,
): GradingSession = aSessionOf(FixtureLoader.facts(fixture), channel, boundErrorText)

/** Same, but for a stream a test has reordered, truncated or amended. */
fun aSessionOf(
    frames: List<GradingFrameFacts>,
    channel: ChannelKey = anAlgorithmChannel(),
    boundErrorText: String? = null,
): GradingSession = anAssembler(channel, boundErrorText).apply { frames.forEach(::accept) }.settle()

/**
 * The nth measured run-path error text, unescaped. Index 0 is the compiler diagnostic and
 * index 1 the runtime stack trace (protocol doc §7) — the two shapes the submit path cannot
 * tell apart on its own.
 */
fun aRunErrorText(index: Int): String {
    val errors = FixtureLoader.messages("algorithm-run-error.jsonl").filterIsInstance<SubmitMessage.Error>()
    return checkNotNull(GradingMessageMapper.errorTextOf(errors[index])) { "run error $index carried no msg" }
}

/**
 * A message type Programmers has never sent us, read the way a real one would be. Kept as a
 * fixture rather than as an empty record so the test still proves what dev rules §2.3 asks:
 * that an unrecognised *message* survives the crossing and arrives inert.
 */
fun anUnmeasuredFrame(): GradingFrameFacts = GradingMessageMapper.factsOf(
    SubmitMessage.ofReceived(
        buildJsonObject {
            put("action", "submit")
            put("type", "notice")
        },
    ),
)
