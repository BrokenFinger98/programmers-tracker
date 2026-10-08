package com.brokenfinger.tracker.adapter.store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributes

/**
 * A read-modify-write state document written temp-then-replace
 * ([[decisions/2026-08-05-write-serialization.md]] decision 3).
 *
 * The timer document and the backup marker are rewritten whole on every change, so a plain
 * write has a window in which the file on disk is neither the old document nor the new one.
 * A reader that lands in that window sees a truncated document — worse than a stale one,
 * because it looks like data rather than like a failure.
 *
 * The temporary file is created **in the target's own directory** so the replace stays a
 * rename within one filesystem; a cross-filesystem move degrades to copy-then-delete, which
 * is precisely the window this class removes.
 *
 * **A rename replaces whatever stands at the target, a link included, and never writes through
 * it** — which is why the push credential and the owner's `.gitignore` are written here too
 * (#360). The temporary file starts owner-only, so a state file is left `rw-------` by every
 * write. [keepsPermissions] is for a document that is not the server's own: the `.gitignore`
 * keeps the permissions its owner gave it.
 */
class AtomicStateFile(private val path: Path, private val keepsPermissions: Boolean = false) {
    private val directory: Path = path.toAbsolutePath().parent

    /** The current document, or null when it has never been written. */
    fun read(): String? = runCatching { Files.readString(path, CHARSET) }.getOrElse { failed(it) }

    /** Replaces the document. A reader sees either the whole previous one or the whole new one. */
    fun write(text: String) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, path.fileName.toString(), SUFFIX)
        runCatching {
            Files.writeString(temp, text, CHARSET)
            if (keepsPermissions) keepPermissions(temp)
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

    /**
     * Hands the document's current permissions to the temporary file, so the replace does not narrow
     * them. Only a regular file's are kept — a link's own bits say nothing about a document — and a
     * filesystem with no POSIX permissions has none to keep. Best effort: a mode that cannot be set
     * leaves the replacement owner-only rather than failing the write.
     */
    private fun keepPermissions(temp: Path) {
        val current = runCatching { Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS) }
        val attributes = current.getOrNull() ?: return
        if (!attributes.isRegularFile) return
        runCatching { Files.setPosixFilePermissions(temp, attributes.permissions()) }
    }

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
