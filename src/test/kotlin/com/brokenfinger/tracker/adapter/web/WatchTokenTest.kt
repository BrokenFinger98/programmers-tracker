package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WatchTokenTest {
    @TempDir
    private lateinit var dir: Path

    @TempDir
    private lateinit var outside: Path

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

    // Written beside and moved into place, owner-only from creation, never through a link (#387) ------------------

    /**
     * Read through a link, the token was whatever the link led to; and a write would have gone through it. A link where
     * the token should be reads as no token now, and the new one replaces it — said, since the extension's copy then
     * stops matching — while the file it led to keeps its bytes. Neither the token nor the link's target is logged.
     */
    @Test
    fun `a link where the token file should be is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(dir), "this test makes symbolic links")
        val elsewhere = Files.writeString(outside.resolve("token-elsewhere"), "$A_TOKEN_ELSEWHERE\n")
        aLink(tokenFile(), elsewhere)
        lateinit var token: WatchToken

        val heard = warningsWhile(WatchToken::class) { token = WatchToken("", tokenFile().toString()) }

        Files.readString(elsewhere) shouldBe "$A_TOKEN_ELSEWHERE\n"
        Files.isSymbolicLink(tokenFile()) shouldBe false
        shouldThrow<UnauthorizedWatchException> { token.verify(A_TOKEN_ELSEWHERE) }
        shouldNotThrowAny { token.verify(tokenFile().readText().trim()) }
        heard.single() shouldContain tokenFile().toString()
        heard.single() shouldNotContain elsewhere.toString()
        // As a boolean, so a failure cannot print the token either.
        heard.single().contains(tokenFile().readText().trim()) shouldBe false
    }

    /** A dangling link read as no token, and the write created the token where it pointed. */
    @Test
    fun `a dangling link where the token file should be creates nothing where it points`() {
        assumeTrue(canPlantLinksIn(dir), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-token")
        aLink(tokenFile(), nowhere)

        WatchToken("", tokenFile().toString())

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.isSymbolicLink(tokenFile()) shouldBe false
    }

    /**
     * The token is written only into a file of its own, created owner-only, and moved over what stood there. Written
     * in place, it went into the file that stood there, under whatever mode that file had until the mode was narrowed
     * after the write — and into every other name that file had. A hard link shows which: its second name keeps what
     * it held.
     */
    @Test
    fun `a blank token file is replaced rather than written into, so a second name for it keeps its bytes`() {
        Files.writeString(tokenFile(), "   \n")
        val secondName = outside.resolve("second-name")
        assumeTrue(runCatching { Files.createLink(secondName, tokenFile()) }.isSuccess, "no hard link between the two")

        WatchToken("", tokenFile().toString())

        // As a boolean, so a failure — the token written into the second name — cannot print it.
        Files.readString(secondName).isBlank() shouldBe true
        tokenFile().readText().trim().shouldNotBeBlank()
    }

    /** Owner-only, whatever someone widened the file it replaced to. On Windows there are no POSIX modes to check. */
    @Test
    fun `a generated token file is owner-only, even where the blank one it replaced was not`() {
        assumeTrue(keepsPosixPermissions(dir), "this test reads POSIX permissions")
        Files.writeString(tokenFile(), "   \n")
        Files.setPosixFilePermissions(tokenFile(), PosixFilePermissions.fromString("rw-rw-rw-"))

        WatchToken("", tokenFile().toString())

        PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile())) shouldBe "rw-------"
    }

    private companion object {
        /** A fake token where a link leads — never a real one. */
        const val A_TOKEN_ELSEWHERE = "a-fake-token-somewhere-else"
    }
}
