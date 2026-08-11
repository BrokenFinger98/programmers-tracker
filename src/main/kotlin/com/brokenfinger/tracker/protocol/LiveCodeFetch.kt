package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.CodeFetch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

/**
 * Measurement entry point for the one Phase 1 gate item still unanswered: **does `run` save
 * the code?** The design admits it does not know (§4.4, §13), and if the answer is no, every
 * `run` record would silently attach the previously saved code.
 *
 * Prints a hash and a preview, never the whole file and never the cookie.
 *
 * Usage: ./gradlew liveCodeFetch -PfetchArgs="120804 java"
 */
fun main(args: Array<String>) {
    if (args.size < 2) return usage()
    val lessonId = args[0].toLongOrNull() ?: return usage()
    val cookie = ManualFileSessionProvider.fromEnvironment().cookie()

    KtorPageSource { cookie.headerValue() }.use { pages ->
        val fetcher = ProblemPageCodeFetcher(pages = pages)
        report(runBlocking { fetcher.fetch(lessonId, args[1]) })
    }
}

private fun report(outcome: CodeFetch) = when (outcome) {
    is CodeFetch.Fetched -> printFetched(outcome.code)
    CodeFetch.Unauthenticated -> println("unauthenticated — the session cookie is no longer valid")
    CodeFetch.RateLimited -> println("rate limited — back off before retrying")
    is CodeFetch.Unavailable -> println("unavailable — ${outcome.reason}")
}

private fun printFetched(code: String) {
    println("sha256   ${sha256(code)}")
    println("length   ${code.length} chars, ${code.lines().size} lines")
    println("preview  ${code.lines().firstOrNull()?.take(60).orEmpty()}")
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private fun usage() {
    println("usage: ./gradlew liveCodeFetch -PfetchArgs=\"<lessonId> <language>\"")
    println("reads the session cookie from .ps/session (never printed)")
}
