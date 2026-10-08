package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.namesIn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * What only a handle gives (#374): a directory swapped for a link once it is open — as a pull can between the check of
 * `.ps` and the write, N10 in the review of #360 — is still the one written in, or takes no write at all. Never the
 * place the link leads. `DirectoryHandleTest` holds the contract a handle shares with a path.
 */
class SecureDirectoryHandleTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private lateinit var state: Path

    @BeforeEach
    fun aStateDirectory() {
        assumeTrue(DirectoryHandles.givesHandles(root), "this platform gives no directory handle")
        state = Files.createDirectory(root.resolve(".ps"))
    }

    @Test
    fun `a directory moved away and replaced by a link is still the one written in`() {
        val aside = root.resolve("aside")

        DirectoryHandles.THROUGH_A_HANDLE.open(state).use { directory ->
            swappedForALink(aside)
            directory.append("s.jsonl", bytes("frame\n"))
            directory.create("timers.json.tmp", OWNER_ONLY)
            directory.write("timers.json.tmp", bytes("{}"))
            directory.move("timers.json.tmp", directory, "timers.json")
        }

        namesIn(outside).shouldBeEmpty()
        namesIn(aside) shouldContainExactly listOf("s.jsonl", "timers.json")
    }

    /** As a checkout that deletes an ignored directory leaves it: no write lands anywhere. */
    @Test
    fun `a directory deleted and replaced by a link takes no write`() {
        DirectoryHandles.THROUGH_A_HANDLE.open(state).use { directory ->
            swappedForALink(aside = null)
            shouldThrow<NoSuchFileException> { directory.append("s.jsonl", bytes("frame\n")) }
            shouldThrow<NoSuchFileException> { directory.create("timers.json.tmp", OWNER_ONLY) }
        }

        namesIn(outside).shouldBeEmpty()
    }

    /** What `StateDirectory` asks once git has answered: the handle is no longer at the path it checked. */
    @Test
    fun `a directory swapped for a link is no longer at its path`() {
        DirectoryHandles.THROUGH_A_HANDLE.open(state).use { directory ->
            directory.isAt(state) shouldBe true
            swappedForALink(root.resolve("aside"))
            directory.isAt(state) shouldBe false
        }
    }

    /**
     * The JDK has no `mkdirat`, so a directory below a handle is made by path: through the link, once the swap is made.
     * It is then opened through the handle, which does not find it, so it takes no write — and an empty directory is
     * left where the link leads, the one trace such a race leaves.
     */
    @Test
    fun `a directory made below one swapped for a link is not opened, and only an empty one is left there`() {
        DirectoryHandles.THROUGH_A_HANDLE.open(state).use { directory ->
            swappedForALink(root.resolve("aside"))
            shouldThrow<NoSuchFileException> { directory.child("raw") }
        }

        namesIn(outside) shouldContainExactly listOf("raw")
        namesIn(outside.resolve("raw")).shouldBeEmpty()
    }

    // The state directory taken away — deleted, as a checkout deletes an ignored one, or moved — and a link put there.
    private fun swappedForALink(aside: Path?) {
        if (aside == null) Files.delete(state)
        if (aside != null) Files.move(state, aside)
        aLink(state, outside)
    }

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private companion object {
        val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
    }
}
