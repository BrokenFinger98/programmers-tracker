package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The second line of defence, for filesystems where the first one silently does nothing.
 *
 * [RecordRepositoryLock] is the right primitive and works wherever locks work. A **Docker
 * Desktop bind mount honours no lock at all and says so by succeeding** — `tryLock` returns a
 * lock that excludes nobody and raises nothing, so neither the refusal nor the escape hatch
 * fires (#52, measured 2026-08-06). That is exactly how `compose.yaml` hands the container
 * the records, so the container-plus-native case the exclusive lock was written for was
 * unprotected on macOS and Windows.
 *
 * This needs only `write` and `stat`, which is all such a filesystem reliably offers.
 *
 * **Change, not age.** A holder rewrites the marker every beat; a starter reads it, waits,
 * and reads again. Changed means somebody is alive. Comparing a timestamp against a local
 * clock would be wrong here in the one case that matters — a container and its host can
 * disagree about what time it is, and an age computed against the wrong clock either refuses
 * a free repository or admits a second writer. Observing that the file *changed* needs no
 * agreement about when.
 *
 * **Strictly weaker than the lock, and that is accepted.** A holder killed hard leaves a
 * marker that stops changing, so the next start waits one window and takes over — no file to
 * delete by hand, which was the pid file's failure. But two instances started inside the same
 * window can both see no change and both proceed. The kernel lock closes that on every
 * filesystem that implements locking; this closes the common case on the one that does not.
 */
class RepositoryHeartbeat(
    private val recordRoot: Path,
    private val marker: Path,
    private val watch: Duration,
    private val waitFor: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : AutoCloseable {
    private val beats = AtomicLong()

    /**
     * @throws RecordRepositoryLockedException when the marker changes while we watch it,
     *   which means another instance is beating.
     */
    fun claim() {
        val before = read() ?: return write()
        // Only paid when a marker exists at all. A repository nobody has claimed starts now.
        waitFor(watch)
        if (read() == before) return takeOver(before)
        throw RecordRepositoryLockedException(
            recordRoot,
            marker,
            refusedBy = RecordRepositoryLockedException.Refusal.HEARTBEAT,
        )
    }

    /** One beat. Must change the file, or watching for change proves nothing. */
    fun beat() = write()

    override fun close() {
        // Removing it saves the next start a watch window. Best-effort: a marker left behind
        // by a crash is exactly the stale case claim() already handles.
        runCatching { Files.deleteIfExists(marker) }
    }

    private fun takeOver(stale: String) {
        logger.info("Took over {} — its marker had stopped changing, so nothing is recording into it", recordRoot)
        logger.debug("Stale marker was {} characters", stale.length)
        write()
    }

    /**
     * Never the reason a capture is lost. This is a safety net over the kernel lock, so a
     * filesystem that refuses to write it leaves the tool running rather than refusing to
     * start — the lock is still in force wherever it works.
     */
    private fun write() {
        runCatching {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, tokenOf(), CREATE, WRITE, TRUNCATE_EXISTING)
        }.onFailure { logger.warn("Could not write the liveness marker for {}; relying on the lock alone", recordRoot) }
    }

    private fun read(): String? = runCatching { Files.readString(marker) }.getOrNull()

    /**
     * Unique per write, which is the only property that matters — the marker is compared for
     * equality, never for order, so nothing here needs to agree with anyone's clock.
     *
     * All three parts earn their place. The pid separates processes; the counter separates
     * beats within one; and `nanoTime` separates two *instances* inside a single process,
     * which a pid and a fresh counter alone do not — a test takeover produced a byte-identical
     * marker and made a live holder look stale.
     */
    private fun tokenOf(): String = "${ProcessHandle.current().pid()}-${System.nanoTime()}-${beats.incrementAndGet()}"

    private companion object {
        val logger = LoggerFactory.getLogger(RepositoryHeartbeat::class.java)!!
    }
}
