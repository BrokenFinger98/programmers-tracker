package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Refused the record repository: somebody else is recording into it, or the filesystem could
 * not say. Carries the paths and nothing else — a record repository path is the user's own,
 * and no credential ever belongs in a message that gets printed on failure.
 */
class RecordRepositoryLockedException(
    val recordRoot: Path,
    val lockFile: Path,
    cause: Throwable? = null,
    /**
     * Which mechanism refused. The two recover differently and telling a user the wrong one
     * sends them to wait for something that will not happen: a kernel lock is released the
     * instant the holder dies, while a heartbeat has to be observed to stop changing.
     */
    val refusedBy: Refusal = Refusal.KERNEL_LOCK,
) : RuntimeException(describe(recordRoot, cause), cause) {
    /**
     * Whether a lock was **refused** (another holder) rather than **unavailable** (the
     * filesystem could not provide one at all). Two different problems with two different
     * answers, and only the second is the user's filesystem rather than their second window.
     */
    val refusedByAnotherHolder: Boolean = cause !is IOException

    /** How the repository was found to be taken. */
    enum class Refusal {
        /** `FileChannel.tryLock` said no — the holder's death releases it immediately. */
        KERNEL_LOCK,

        /** The marker kept changing while we watched — recovery takes one watch window. */
        HEARTBEAT,
    }

    private companion object {
        fun describe(recordRoot: Path, cause: Throwable?): String {
            if (cause is IOException) return "The filesystem holding $recordRoot could not provide a lock"
            return "Another programmers-tracker instance is already recording into $recordRoot"
        }
    }
}

/**
 * The exclusive, process-wide claim on one record repository, held for as long as this
 * instance runs ([[decisions/2026-08-05-write-serialization]] decision 5).
 *
 * **Why a kernel lock and not a pid file.** The double-writer this exists to stop is a
 * container and a native run bind-mounted onto the same records directory. A pid from another
 * namespace means nothing on this side of the mount, and a pid file outlives a `kill -9` — so
 * every stale one has to be guessed about. `FileChannel.tryLock` is advisory but
 * kernel-enforced across processes, and the operating system releases it when the holder
 * dies, however it dies. That property is a test, not a comment.
 *
 * **Where the file lives, and why that is the interesting part.** `CommandLineGitSync`
 * reconciles with `git add --all` over the whole record repository, so a lock file anywhere
 * git can see gets committed and then pushed to a public records repository. `.git/` is
 * never tracked by git, so that is where the lock goes. A records directory nobody ran
 * `git init` on has no `.git/`, and there the lock falls back to the repository root — still
 * cross-process, because what is shared is the mount and not the repository, and safe because
 * a directory with no repository has nothing that could commit it. The one window that leaves
 * — a run before `git init`, then `git init` — is closed from both sides: the root file is
 * removed as soon as a `.git` exists, and its name is in the template `.gitignore`.
 *
 * **What it does not cover.** `.ps/` (raw frames, timers, the backup marker) is shared
 * between a container and a native run just as the records are, and is not the record
 * repository. Nothing here locks it. Neither is the guarantee universal: a lock is only as
 * good as the filesystem underneath, and virtualised Docker mounts and network filesystems
 * are unverified here (see the ADR).
 */
class RecordRepositoryLock(recordRoot: Path) : AutoCloseable {
    /** Where the claim physically sits — inside `.git` whenever the repository has one. */
    val file: Path = lockFileIn(recordRoot)

    private val channel: FileChannel = FileChannel.open(file, CREATE, WRITE)
    private val lock: FileLock = exclusiveOn(recordRoot)

    init {
        explainItself()
        logger.info("Holding {} exclusively — no second instance may record into it", recordRoot)
    }

    /** Releases the repository. The operating system does the same thing if this never runs. */
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    /**
     * @throws RecordRepositoryLockedException when another process holds the repository, when
     *   this JVM already does, or when the filesystem cannot lock at all.
     */
    private fun exclusiveOn(recordRoot: Path): FileLock {
        try {
            // null means another PROCESS holds it; the exception means this JVM already does.
            // Handling only the first would let a second context boot inside one process.
            return channel.tryLock() ?: refuse(recordRoot, null)
        } catch (overlapping: OverlappingFileLockException) {
            refuse(recordRoot, overlapping)
        } catch (unsupported: IOException) {
            refuse(recordRoot, unsupported)
        }
    }

    private fun refuse(recordRoot: Path, cause: Throwable?): Nothing {
        runCatching { channel.close() }
        throw RecordRepositoryLockedException(recordRoot, file, cause)
    }

    // Decorative, never read back: whoever finds this file in their records directory
    // deserves a sentence telling them what it is. Best-effort for the same reason.
    private fun explainItself() {
        runCatching {
            channel.truncate(0)
            channel.write(ByteBuffer.wrap(NOTE.toByteArray(UTF_8)))
        }
    }

    companion object {
        /** Dotted so it is never confused with git's own `index.lock` and friends. */
        const val LOCK_FILE = ".programmers-tracker.lock"

        private const val GIT = ".git"
        private const val GIT_DIR = "gitdir:"

        private val NOTE =
            "programmers-tracker holds this file open while it records into this repository.\n" +
                "It is not a pid file: the lock is released by the operating system when that\n" +
                "process exits, however it exits. Deleting the file releases nothing.\n"

        private val logger = LoggerFactory.getLogger(RecordRepositoryLock::class.java)

        private fun lockFileIn(recordRoot: Path): Path {
            Files.createDirectories(recordRoot)
            val gitDirectory = gitDirectoryOf(recordRoot) ?: return recordRoot.resolve(LOCK_FILE)
            removeStaleRootLock(recordRoot)
            return gitDirectory.resolve(LOCK_FILE)
        }

        /**
         * The repository's own directory: `.git` when it is one, and what its `gitdir:` line
         * points at when a linked worktree left a file there instead.
         *
         * An unreadable pointer falls back to the root, which is safe by consequence rather
         * than by luck: git itself cannot run in a repository whose `.git` file it cannot
         * follow, so `CommandLineGitSync` finds no repository and commits nothing at all.
         */
        private fun gitDirectoryOf(recordRoot: Path): Path? {
            val git = recordRoot.resolve(GIT)
            if (Files.isDirectory(git)) return git
            if (!Files.isRegularFile(git)) return null
            return linkedGitDirectoryIn(recordRoot, git)
        }

        private fun linkedGitDirectoryIn(recordRoot: Path, pointer: Path): Path? =
            runCatching { Files.readString(pointer) }.getOrNull()
                ?.lineSequence()
                ?.firstOrNull { it.startsWith(GIT_DIR) }
                ?.removePrefix(GIT_DIR)
                ?.trim()
                // Git writes this path absolute, but it is allowed to be relative to the
                // worktree, and `resolve` leaves an absolute one alone.
                ?.let { recordRoot.resolve(it) }
                ?.takeIf { Files.isDirectory(it) }

        /**
         * A lock left in the working tree by a run from before `git init`. It is ours, it is
         * stale by construction — this instance is about to hold the one inside `.git` — and
         * left there `git add --all` would publish it to the user's records repository.
         */
        private fun removeStaleRootLock(recordRoot: Path) {
            runCatching { Files.deleteIfExists(recordRoot.resolve(LOCK_FILE)) }
        }
    }
}
