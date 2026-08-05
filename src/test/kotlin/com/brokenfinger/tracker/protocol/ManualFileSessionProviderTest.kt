package com.brokenfinger.tracker.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManualFileSessionProviderTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `passes a full cookie header through unchanged`() {
        val file = sessionFile("_session_production=abc; tracking_id=t1")

        ManualFileSessionProvider(file).cookie().headerValue() shouldBe "_session_production=abc; tracking_id=t1"
    }

    @Test
    fun `wraps a bare value as the session cookie`() {
        val file = sessionFile("abc123")

        ManualFileSessionProvider(file).cookie().headerValue() shouldBe "_session_production=abc123"
    }

    @Test
    fun `trims surrounding whitespace and newlines`() {
        val file = sessionFile("  abc123\n")

        ManualFileSessionProvider(file).cookie().headerValue() shouldBe "_session_production=abc123"
    }

    @Test
    fun `missing file fails with the path and a hint, never a cookie`() {
        val missing = dir.resolve("nope")

        val exception = shouldThrow<IllegalStateException> { ManualFileSessionProvider(missing).cookie() }

        exception.message shouldContain missing.toString()
        exception.message shouldContain "TRACKER_SESSION_FILE"
    }

    @Test
    fun `empty file fails with the path only`() {
        val file = sessionFile("leaky-value").also { Files.writeString(it, "  \n") }

        val exception = shouldThrow<IllegalStateException> { ManualFileSessionProvider(file).cookie() }

        exception.message shouldContain file.toString()
        exception.message shouldNotContain "leaky-value"
    }

    @Test
    fun `fromEnvironment reads the configured file path`() {
        val file = sessionFile("abc123")

        val provider = ManualFileSessionProvider.fromEnvironment { name ->
            file.toString().takeIf { name == "TRACKER_SESSION_FILE" }
        }

        provider.cookie().headerValue() shouldBe "_session_production=abc123"
    }

    @Test
    fun `fromEnvironment defaults to the project-local session file`() {
        val provider = ManualFileSessionProvider.fromEnvironment { null }

        provider.path shouldBe Path.of(".ps/session")
    }

    @Test
    fun `fromEnvironment still expands a tilde in the override`() {
        val provider = ManualFileSessionProvider.fromEnvironment { name ->
            "~/elsewhere/session".takeIf { name == "TRACKER_SESSION_FILE" }
        }

        provider.path.toString() shouldStartWith System.getProperty("user.home")
    }

    private fun sessionFile(content: String): Path = dir.resolve("session").also { Files.writeString(it, content) }
}
