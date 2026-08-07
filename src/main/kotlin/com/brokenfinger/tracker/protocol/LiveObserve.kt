package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.domain.ChallengeableId
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.LessonId
import com.brokenfinger.tracker.protocol.parse.GradingMessageMapper
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Live-verification entry point (issue #6): subscribes to one real Programmers channel
 * and logs every received frame with its raw JSON, so a human can trigger `run`/`submit`
 * in the browser and confirm the passive-observation architecture from Kotlin
 * (protocol doc §10). The session cookie is never printed (dev rules §7.2).
 *
 * Run: ./gradlew liveObserve -Pobserve="algorithm 120804 14643 java"
 */
fun main(args: Array<String>) {
    val identifier = parseTarget(args) ?: exitWithUsage()
    val provider = ManualFileSessionProvider.fromEnvironment()
    val endpoint = CableEndpoint.fromEnvironment()
    printBanner(endpoint, provider, identifier)
    runBlocking { ActionCableClient(endpoint).observe(identifier, provider).collect(::printEvent) }
}

internal fun parseTarget(args: Array<String>): ChannelIdentifier? {
    if (args.size != 4) return null
    return runCatching {
        ChannelIdentifier.from(
            ChannelKey.of(
                LessonId(args[1].toLong()),
                ChallengeableId(args[2].toLong()),
                GradingMessageMapper.problemKindOf(ChallengeableType.from(args[0])),
                args[3],
            ),
        )
    }.getOrNull()
}

private fun printBanner(endpoint: CableEndpoint, provider: ManualFileSessionProvider, identifier: ChannelIdentifier) {
    println("Connecting to ${endpoint.url} (origin ${endpoint.origin})")
    println("Session cookie file: ${provider.path} (value is never printed)")
    println("Subscribing with identifier: ${identifier.asJson()}")
    println("Now trigger 'run' or 'submit' for this problem in your browser and watch the frames below.")
    println("Stop with Ctrl+C. A timeout verdict can take ~87 s to finish (protocol doc §13.5) — keep waiting.")
}

private fun printEvent(event: CableEvent) = when (event) {
    is CableEvent.SubscriptionConfirmed -> println("[${elapsed()}s confirmed] ${event.rawText}")
    is CableEvent.MessageReceived -> println("[${elapsed()}s broadcast] ${event.rawText}")
    is CableEvent.Unhandled -> println("[${elapsed()}s unhandled] ${event.rawText}")
    // Every 3 seconds and content-free. Printed anyway: this tool exists to watch what the
    // socket really does, and the heartbeat's cadence is part of that.
    is CableEvent.Heartbeat -> println("[${elapsed()}s ping]")
}

private val startedAt = System.nanoTime()

private fun elapsed(): String = "%.2f".format((System.nanoTime() - startedAt) / 1_000_000_000.0)

private fun exitWithUsage(): Nothing {
    println("Usage: ./gradlew liveObserve -Pobserve=\"<algorithm|database> <lessonId> <challengeableId> <language>\"")
    println("  Example: ./gradlew liveObserve -Pobserve=\"algorithm 120804 14643 java\"")
    println("  Identifiers come from the problem page: URL path (lessonId) and the")
    println("  data-challengeable-id / data-challengeable-type attributes (protocol doc §3).")
    println("  Session cookie: paste your _session_production value into .ps/session")
    println("  (or point TRACKER_SESSION_FILE elsewhere). The value is never printed.")
    exitProcess(1)
}
