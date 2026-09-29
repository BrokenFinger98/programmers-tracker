package com.brokenfinger.tracker.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

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

    // ---- #332: the sensor hands over a value ----

    @Test
    fun `replacing writes the value owner-only and serves it from then on`() {
        val file = sessionFile("old-value")
        val provider = ManualFileSessionProvider(file)

        val result = provider.replace("new-value")

        result shouldBe SessionReplacement(changed = true, persisted = true)
        Files.readString(file) shouldBe "new-value"
        provider.cookie().headerValue() shouldBe "_session_production=new-value"
        runCatching { Files.getPosixFilePermissions(file) }.getOrNull()?.let {
            PosixFilePermissions.toString(it) shouldBe "rw-------"
        }
    }

    @Test
    fun `replacing with the value already held changes nothing`() {
        val file = sessionFile("same-value")
        val provider = ManualFileSessionProvider(file)

        provider.replace("same-value") shouldBe SessionReplacement(changed = false, persisted = true)
        provider.replace("_session_production=same-value") shouldBe
            SessionReplacement(changed = false, persisted = true)
    }

    @Test
    fun `replacing a missing file creates it`() {
        val file = dir.resolve("not-yet").resolve("session")
        val provider = ManualFileSessionProvider(file)

        provider.replace("fresh") shouldBe SessionReplacement(changed = true, persisted = true)

        Files.readString(file) shouldBe "fresh"
    }

    @Test
    fun `a blank value is refused before anything is written`() {
        val file = sessionFile("kept")

        shouldThrow<IllegalArgumentException> { ManualFileSessionProvider(file).replace("   ") }

        Files.readString(file) shouldBe "kept"
    }

    @Test
    fun `a file that cannot be written still serves the new value, and says it was not persisted`() {
        assumeTrue(posixPermissions(), "this test denies the write with POSIX permissions")
        val readOnlyDir = Files.createDirectory(dir.resolve("ro"))
        val file = readOnlyDir.resolve("session")
        Files.writeString(file, "old-value")
        Files.setPosixFilePermissions(readOnlyDir, PosixFilePermissions.fromString("r-x------"))
        try {
            val provider = ManualFileSessionProvider(file)

            provider.replace("new-value") shouldBe SessionReplacement(changed = true, persisted = false)

            provider.cookie().headerValue() shouldBe "_session_production=new-value"
            Files.readString(file) shouldBe "old-value"
        } finally {
            Files.setPosixFilePermissions(readOnlyDir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    /** Review of #332: a write that failed is tried again on the next hand-off, even of the same value. */
    @Test
    fun `a value that could not be written is written on the next hand-off once the location allows it`() {
        assumeTrue(posixPermissions(), "this test denies the write with POSIX permissions")
        val lockedDir = Files.createDirectory(dir.resolve("locked"))
        val file = lockedDir.resolve("session")
        Files.writeString(file, "old-value")
        Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-x------"))
        val provider = ManualFileSessionProvider(file)
        try {
            provider.replace("new-value") shouldBe SessionReplacement(changed = true, persisted = false)
        } finally {
            Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwx------"))
        }

        provider.replace("new-value") shouldBe SessionReplacement(changed = false, persisted = true)

        Files.readString(file) shouldBe "new-value"
        provider.cookie().headerValue() shouldBe "_session_production=new-value"
    }

    /** Review of #332: a hand-pasted file must not stay shadowed by a value the server could not write. */
    @Test
    fun `a file changed by hand wins over a value held in memory`() {
        assumeTrue(posixPermissions(), "this test denies the write with POSIX permissions")
        val lockedDir = Files.createDirectory(dir.resolve("locked2"))
        val file = lockedDir.resolve("session")
        Files.writeString(file, "old-value")
        Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("r-x------"))
        val provider = ManualFileSessionProvider(file)
        try {
            provider.replace("from-sensor")
            provider.cookie().headerValue() shouldBe "_session_production=from-sensor"
        } finally {
            Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("rwx------"))
        }

        Files.writeString(file, "pasted-by-hand")

        provider.cookie().headerValue() shouldBe "_session_production=pasted-by-hand"
    }

    /** Review of #332: a parent directory that cannot be searched is an error, not a missing cookie. */
    @Test
    fun `an unreadable location is not reported as missing`() {
        assumeTrue(posixPermissions(), "this test denies the write with POSIX permissions")
        val sealed = Files.createDirectory(dir.resolve("sealed"))
        val file = sealed.resolve("session")
        Files.writeString(file, "present")
        Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("---------"))
        try {
            val thrown = runCatching { ManualFileSessionProvider(file).cookie() }.exceptionOrNull()
            (thrown is MissingSessionException) shouldBe false
            (thrown == null) shouldBe false
        } finally {
            Files.setPosixFilePermissions(sealed, PosixFilePermissions.fromString("rwx------"))
        }
    }

    /** Decided by the cookie's name, not by "=": a bare value may carry base64 padding. */
    @Test
    fun `a bare value with padding is still wrapped, and a named header passes through`() {
        ManualFileSessionProvider(sessionFile("abc==")).cookie().headerValue() shouldBe "_session_production=abc=="
        ManualFileSessionProvider(sessionFile("x=1; _session_production=abc")).cookie().headerValue() shouldBe
            "x=1; _session_production=abc"
    }

    @Test
    fun `an absent or empty file is a missing session, not a generic failure`() {
        shouldThrow<MissingSessionException> { ManualFileSessionProvider(dir.resolve("nope")).cookie() }
        shouldThrow<MissingSessionException> { ManualFileSessionProvider(sessionFile("  ")).cookie() }
    }

    /** Windows has no POSIX permissions to deny a write with; these tests skip there, as GithubRemoteTest does. */
    private fun posixPermissions(): Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}
