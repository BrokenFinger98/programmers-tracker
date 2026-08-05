package com.brokenfinger.tracker.adapter.web

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WatchTokenTest {
    @TempDir
    private lateinit var dir: Path

    private fun tokenFile(): Path = dir.resolve("watch-token")

    @Test
    fun `accepts the configured token`() {
        val token = WatchToken("configured-token", tokenFile().toString())

        shouldNotThrowAny { token.verify("configured-token") }
    }

    @Test
    fun `rejects a wrong token`() {
        val token = WatchToken("configured-token", tokenFile().toString())

        shouldThrow<UnauthorizedWatchException> { token.verify("wrong-token") }
    }

    @Test
    fun `rejects a missing token`() {
        val token = WatchToken("configured-token", tokenFile().toString())

        shouldThrow<UnauthorizedWatchException> { token.verify(null) }
        shouldThrow<UnauthorizedWatchException> { token.verify("   ") }
    }

    @Test
    fun `a configured token never touches the token file`() {
        WatchToken("configured-token", tokenFile().toString())

        Files.exists(tokenFile()) shouldBe false
    }

    @Test
    fun `generates and persists a token when none is configured`() {
        val token = WatchToken("", tokenFile().toString())
        val persisted = tokenFile().readText().trim()

        persisted.shouldNotBeBlank()
        persisted.length shouldBeGreaterThanOrEqualTo 32
        shouldNotThrowAny { token.verify(persisted) }
    }

    @Test
    fun `reuses the persisted token across restarts so the extension keeps working`() {
        val first = WatchToken("", tokenFile().toString())
        val persisted = tokenFile().readText().trim()

        val second = WatchToken("", tokenFile().toString())

        shouldNotThrowAny { second.verify(persisted) }
        shouldNotThrowAny { first.verify(persisted) }
    }

    @Test
    fun `two fresh installations do not share a token`() {
        val first = tokenFile()
        val second = dir.resolve("other-token")

        WatchToken("", first.toString())
        WatchToken("", second.toString())

        first.readText() shouldNotBe second.readText()
    }

    @Test
    fun `regenerates when the persisted token file is empty`() {
        tokenFile().writeText("   \n")

        val token = WatchToken("", tokenFile().toString())

        val persisted = tokenFile().readText().trim()
        persisted.shouldNotBeBlank()
        shouldNotThrowAny { token.verify(persisted) }
    }

    @Test
    fun `never reveals the expected token in its string form`() {
        val token = WatchToken("configured-token", tokenFile().toString())

        token.toString() shouldBe "WatchToken(***)"
    }
}
