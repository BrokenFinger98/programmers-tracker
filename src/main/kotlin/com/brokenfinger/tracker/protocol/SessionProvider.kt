package com.brokenfinger.tracker.protocol

import java.nio.file.Files
import java.nio.file.Path

/** Single access point for the session cookie (dev rules §7.2, §9.2). */
fun interface SessionProvider {
    fun cookie(): SessionCookie
}

/**
 * Mandatory fallback that works on every platform: the user pastes the cookie into a
 * gitignored file (dev rules §9.2). Accepts either a full `Cookie` header value or the
 * bare `_session_production` value. Browser auto-extraction is a separate provider.
 */
class ManualFileSessionProvider(val path: Path) : SessionProvider {
    override fun cookie(): SessionCookie = SessionCookie(asCookieHeader(readValue()))

    private fun readValue(): String {
        check(Files.exists(path)) {
            "Session file not found: $path — paste your _session_production cookie value into it, " +
                "or point $SESSION_FILE_ENV at another file"
        }
        return Files.readString(path).trim().also {
            check(it.isNotEmpty()) { "Session file is empty: $path" }
        }
    }

    private fun asCookieHeader(text: String): String {
        if (text.contains('=')) return text
        return "_session_production=$text"
    }

    companion object {
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
