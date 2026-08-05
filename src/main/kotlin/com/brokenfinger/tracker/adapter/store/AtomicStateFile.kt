package com.brokenfinger.tracker.adapter.store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A read-modify-write state document written temp-then-replace
 * ([[decisions/2026-08-05-write-serialization.md]] decision 3).
 *
 * `.ps/timers.json` and `.ps/hints.json` are rewritten whole on every change, so a plain
 * write has a window in which the file on disk is neither the old document nor the new one.
 * A reader that lands in that window sees a truncated document — worse than a stale one,
 * because it looks like data rather than like a failure.
 *
 * The temporary file is created **in the target's own directory** so the replace stays a
 * rename within one filesystem; a cross-filesystem move degrades to copy-then-delete, which
 * is precisely the window this class removes.
 */
class AtomicStateFile(private val path: Path) {
    private val directory: Path = path.toAbsolutePath().parent

    /** The current document, or null when it has never been written. */
    fun read(): String? = runCatching { Files.readString(path, CHARSET) }.getOrElse { failed(it) }

    /** Replaces the document. A reader sees either the whole previous one or the whole new one. */
    fun write(text: String) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, path.fileName.toString(), SUFFIX)
        runCatching {
            Files.writeString(temp, text, CHARSET)
            replace(temp)
        }.onFailure {
            Files.deleteIfExists(temp)
            throw it
        }
    }

    /**
     * Reads, transforms and replaces in one call. A transform that throws leaves the previous
     * document exactly as it was — nothing is written and no debris is left behind.
     */
    fun update(transform: (String?) -> String) = write(transform(read()))

    // ATOMIC_MOVE is the guarantee we want; where the filesystem cannot give it, an ordinary
    // replace is still better than writing the target in place.
    private fun replace(temp: Path) {
        runCatching { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun failed(cause: Throwable): String? {
        if (cause is NoSuchFileException) return null
        throw cause
    }

    companion object {
        private val CHARSET = StandardCharsets.UTF_8
        private const val SUFFIX = ".tmp"
        private const val STATE_DIRECTORY = ".ps"

        /** State documents live under the record repository, not next to the tool (design §5.1). */
        fun under(recordRoot: Path, name: String): AtomicStateFile =
            AtomicStateFile(recordRoot.resolve(STATE_DIRECTORY).resolve(name))
    }
}
