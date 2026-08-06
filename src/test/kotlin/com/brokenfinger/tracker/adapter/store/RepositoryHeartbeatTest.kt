package com.brokenfinger.tracker.adapter.store

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The second line of defence, for filesystems where the first one silently does nothing.
 *
 * `FileChannel.tryLock` is the right primitive and works wherever locks work — but a Docker
 * Desktop bind mount honours no lock at all and **says so by succeeding** (#52, measured
 * 2026-08-06). That is the container-plus-native case the exclusive lock was written for, so
 * on macOS and Windows it protected nothing.
 *
 * These tests describe a mechanism that needs only `write` and `stat`, because that is all
 * such a filesystem reliably offers.
 */
class RepositoryHeartbeatTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `an empty repository is free`() {
        heartbeat().claim()

        marker().shouldExist()
    }

    @Test
    fun `claiming writes a marker that later readers can see`() {
        heartbeat().claim()

        Files.readString(marker()).isNotBlank().shouldBeTrue()
    }

    /**
     * The decisive property, and the reason this does not compare timestamps to a local
     * clock: a container and its host can disagree about what time it is, and an age computed
     * against the wrong clock either refuses a free repository or admits a second writer.
     * Observing that the marker **changed** needs no agreement about when.
     */
    @Test
    fun `a marker that changes while we watch means somebody is alive`() {
        val other = heartbeat()
        other.claim()

        val refusal = shouldThrow<RecordRepositoryLockedException> {
            heartbeat(onWait = { other.beat() }).claim()
        }

        refusal.message.orEmpty() shouldContain root.toString()
    }

    /**
     * The other half: a holder that died leaves a marker nobody is updating. It must not
     * block the next start, and must not need a human to delete a file — the failure a pid
     * file has and the reason the kernel lock was preferred in the first place.
     */
    @Test
    fun `a marker nobody is updating is stale, and the repository is taken over`() {
        heartbeat().claim()

        heartbeat().claim()

        marker().shouldExist()
    }

    @Test
    fun `taking over a stale marker replaces it rather than appending`() {
        heartbeat().claim()
        val stale = Files.readString(marker())

        heartbeat().claim()

        (Files.readString(marker()) == stale).shouldBeFalse()
    }

    /** A beat has to actually change the file, or watching for change proves nothing. */
    @Test
    fun `each beat changes the marker`() {
        val holder = heartbeat()
        holder.claim()
        val first = Files.readString(marker())

        holder.beat()

        (Files.readString(marker()) == first).shouldBeFalse()
    }

    /**
     * The watch is the whole cost of this mechanism, so it is bounded and only paid when a
     * marker exists. A repository nobody has ever claimed must start immediately.
     */
    @Test
    fun `a repository with no marker is claimed without waiting`() {
        var waited = false

        heartbeat(onWait = { waited = true }).claim()

        waited.shouldBeFalse()
    }

    /**
     * Never the reason a capture is lost. The marker is a safety net; a filesystem that
     * refuses to write it leaves the tool running rather than refusing to start, because the
     * kernel lock is still in force wherever it works.
     */
    @Test
    fun `a marker that cannot be written does not stop the tool`() {
        val unwritable = root.resolve("no-such-directory/marker")

        RepositoryHeartbeat(root, unwritable, WATCH, waitFor = {}).claim()
    }

    @Test
    fun `releasing removes the marker so the next start does not have to wait it out`() {
        val holder = heartbeat()
        holder.claim()

        holder.close()

        Files.exists(marker()).shouldBeFalse()
    }

    private fun heartbeat(onWait: () -> Unit = {}) = RepositoryHeartbeat(root, marker(), WATCH, waitFor = { onWait() })

    private fun marker(): Path = root.resolve(".programmers-tracker.alive")

    private fun Path.shouldExist() = Files.exists(this) shouldBe true

    private companion object {
        val WATCH: Duration = Duration.ofMillis(1)
    }
}
