package com.brokenfinger.tracker.protocol

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Single access point for the session cookie (dev rules §7.2, §9.2). */
fun interface SessionProvider {
    fun cookie(): SessionCookie
}

/** There is no credential to present: the file is absent or empty and nothing has supplied one (#332). */
class MissingSessionException(message: String) : IllegalStateException(message)

/** What handing the provider a new value came to (#332). */
data class SessionReplacement(val changed: Boolean, val persisted: Boolean)

/**
 * Mandatory fallback that works on every platform: the user pastes the cookie into a
 * gitignored file (dev rules §9.2). Accepts either a full `Cookie` header value or the
 * bare `_session_production` value. Browser auto-extraction is a separate provider.
 */
class ManualFileSessionProvider(val path: Path) : SessionProvider {
    /** Served ahead of the file only while the file could not be written — see [replace]. */
    @Volatile
    private var unpersisted: String? = null

    /** What the file held when the write failed; the memory value shadows the file only while that is still true. */
    @Volatile
    private var shadowedFile: String? = null

    override fun cookie(): SessionCookie = SessionCookie(asCookieHeader(stillShadowing() ?: readValue()))

    // A value served from memory shadows the file only while the file is what it was when the
    // write failed. Once the file changes under it — a hand-pasted replacement — the file wins.
    private fun stillShadowing(): String? = synchronized(this) {
        val memory = unpersisted ?: return null
        if (runCatching { readValue() }.getOrNull() == shadowedFile) return memory
        unpersisted = null
        shadowedFile = null
        null
    }

    /**
     * Takes a new value from the sensor (#332). Written owner-only and atomically beside the
     * file, so a torn read cannot happen and a restart keeps it. A file that cannot be written —
     * a read-only mount — does not fail the hand-off: the value is served from memory and the
     * caller is told it was not persisted. The value itself reaches no log; the path does.
     */
    fun replace(value: String): SessionReplacement = synchronized(this) {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "session cookie must not be blank" }
        val current = runCatching { cookie().headerValue() }.getOrNull()
        val same = current == asCookieHeader(trimmed)
        if (same && unpersisted == null) return@synchronized SessionReplacement(changed = false, persisted = true)
        // A new value — or the same one that could not be written last time, tried again: a mount
        // that became writable must not need a different cookie to be noticed.
        val persisted = runCatching { writeAtomically(trimmed) }
            .onFailure {
                logger.warn("The session cookie was replaced but could not be written to {}: {}", path, it.message)
            }
            .isSuccess
        if (persisted) {
            unpersisted = null
            shadowedFile = null
            logger.info("The session cookie was replaced and written to {}", path)
        } else {
            unpersisted = trimmed
            shadowedFile = runCatching { readValue() }.getOrNull()
        }
        SessionReplacement(changed = !same, persisted = persisted)
    }

    private fun writeAtomically(text: String) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".session-", ".tmp")
        try {
            Files.writeString(temporary, text)
            runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")) }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    // Absent is MISSING; unreadable is an error and stays one. `Files.exists` would answer false
    // for a parent directory it cannot search and turn a good cookie into "paste one".
    private fun readValue(): String {
        val text = try {
            Files.readString(path)
        } catch (absent: NoSuchFileException) {
            throw MissingSessionException(
                "Session file not found: $path — paste your _session_production cookie value into it, " +
                    "or point $SESSION_FILE_ENV at another file",
            )
        }
        return text.trim().also {
            if (it.isEmpty()) throw MissingSessionException("Session file is empty: $path")
        }
    }

    // The file holds either the bare value or a header that names the cookie. Decided by the
    // name, not by the presence of "=": a bare value may carry base64 padding.
    private fun asCookieHeader(text: String): String {
        if (text.contains("_session_production=")) return text
        return "_session_production=$text"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ManualFileSessionProvider::class.java)
        private const val SESSION_FILE_ENV = "TRACKER_SESSION_FILE"

        // Project-local by design: distributed users find `.ps/` next to the tool, and the
        // repo's .gitignore shields it. Override with TRACKER_SESSION_FILE for other layouts.
        //
        // Deliberately NOT where the rest of the state went (#126). Timers, raw frames and the
        // backup marker moved into the record repository because they describe the records;
        // this is a credential, and the record repository is pushed.
        private const val DEFAULT_PATH = ".ps/session"

        fun fromEnvironment(env: (String) -> String? = System::getenv): ManualFileSessionProvider =
            ManualFileSessionProvider(expandHome(env(SESSION_FILE_ENV) ?: DEFAULT_PATH))

        // A leading tilde only, by concatenation: replaceFirst would rewrite a tilde
        // anywhere in the string, and a Windows short path contains one
        // (C:\Users\RUNNER~1\AppData\Local\Temp). Measured in CI 2026-08-06.
        private fun expandHome(raw: String): Path {
            if (!raw.startsWith("~")) return Path.of(raw)
            return Path.of(System.getProperty("user.home") + raw.removePrefix("~"))
        }
    }
}
