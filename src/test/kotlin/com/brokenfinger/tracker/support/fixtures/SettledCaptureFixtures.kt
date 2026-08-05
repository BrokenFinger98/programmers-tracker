package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.GradingSession
import com.brokenfinger.tracker.application.RawSessionId
import com.brokenfinger.tracker.application.SettledCapture
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import com.brokenfinger.tracker.protocol.parse.GradingMessageMapper

// Object mothers (dev rules §6.4). The default capture is the measured passing algorithm
// submit of fixtures/algorithm-pass.jsonl, already settled and ready to be recorded.
// Korean literals are measured values kept verbatim (protocol doc §7).

fun aSettledCapture(
    session: GradingSession = anAssembledSession("algorithm-pass.jsonl"),
    rawSessionId: RawSessionId = aRawSessionId(),
    lessonId: Long = 120804,
    title: String = "두 수의 곱 구하기",
    language: String = "java",
    elapsedSec: Long = 847,
    terminalFrame: String? = aTerminalFrame("algorithm-pass.jsonl"),
) = SettledCapture(
    session = session,
    rawSessionId = rawSessionId,
    lessonId = lessonId,
    title = title,
    language = language,
    elapsedSec = elapsedSec,
    terminalFrame = terminalFrame,
)

fun aRawSessionId(name: String = "20260804T142301000Z-120804.jsonl") = RawSessionId(name)

/** The last frame of a measured capture — what actually ended that stream (dev rules §6.2). */
fun aTerminalFrame(fixture: String): String = FixtureLoader.rawFrames(fixture).last()

/** A measured stream with every terminal frame removed — the shape a timeout leaves (design §4.2). */
fun aTruncatedStream(fixture: String = "algorithm-pass.jsonl"): List<SubmitMessage> =
    FixtureLoader.messages(fixture).filter { GradingMessageMapper.terminalKindOf(it) == null }
