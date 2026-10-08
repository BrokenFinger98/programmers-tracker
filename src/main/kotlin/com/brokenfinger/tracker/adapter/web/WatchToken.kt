package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/** The caller presented no token, or the wrong one. Carries no detail on purpose. */
class UnauthorizedWatchException : RuntimeException("a valid ${WatchController.TOKEN_HEADER} header is required")

/**
 * Local authorization for `/watch`. The endpoint is loopback-only, but loopback is shared
 * with every other process and every page in the browser, so a token is still required
 * (design §4.1) — this server holds a live session cookie and can push to the record repo.
 *
 * **There is no default token value and authorization is never off.** When
 * `tracker.watch.token` is unset the server generates a 256-bit token on first start and
 * persists it owner-only at `tracker.watch.token-file` (`.ps/watch-token`, already
 * gitignored). Persisting rather than regenerating per run is what makes it usable: the
 * extension heartbeats every 30 s, and a token that changed on every restart would turn
 * every heartbeat into a silent 401. The value is never logged — only the path is, so the
 * user can copy it into the extension.
 *
 * **Owner-only from creation, and never through a link** (#387). The token is written into a
 * temporary file beside its own, created owner-only (`rw-------`), and moved over it, by
 * [AtomicStateFile] — as the push credential is. It used to be written in place and narrowed
 * after, so it sat in a file others could read until the narrowing, and went through a link or
 * into every other name the file had. A link where the token should be reads as no token, and
 * the new one replaces it: said, since the extension's copy then stops matching. On Windows,
 * which has no POSIX permissions, the file gets what its directory gives a new one, as before.
 *
 * It stays beside the tool rather than joining the state that moved into the record
 * repository (#126): that state describes the records and travels with them, while this is a
 * credential and the record repository is pushed.
 */
@Component
class WatchToken(
    @Value("\${tracker.watch.token:}") configured: String,
    @Value("\${tracker.watch.token-file:.ps/watch-token}") tokenFile: String,
) {
    private val file: Path = Path.of(tokenFile)
    private val state = AtomicStateFile(file)
    private val expected: ByteArray = resolve(configured.trim()).toByteArray(UTF_8)

    /** @throws UnauthorizedWatchException when the presented value is absent or does not match. */
    fun verify(presented: String?) {
        val candidate = presented?.trim().orEmpty()
        if (candidate.isEmpty()) throw UnauthorizedWatchException()
        if (!MessageDigest.isEqual(expected, candidate.toByteArray(UTF_8))) throw UnauthorizedWatchException()
    }

    /** Masked like SessionCookie — a credential must not be printable by accident (dev rules §7.2). */
    override fun toString(): String = "WatchToken(***)"

    private fun resolve(configured: String): String {
        if (configured.isNotBlank()) return configured
        return persisted() ?: generated()
    }

    // Read without following a link: one where the token should be reads as no token, which the new one replaces.
    private fun persisted(): String? = runCatching { state.read() }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    private fun generated(): String {
        val generated = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
        store(generated)
        return generated
    }

    private fun store(generated: String) {
        if (Files.isSymbolicLink(file)) log.warn(REPLACING_A_LINK, file)
        runCatching { state.write(generated) }
            .onSuccess { log.info("Generated a local /watch token at {} — paste it into the extension.", file) }
            .onFailure { log.warn("Could not persist the generated /watch token at {}: {}", file, it.message) }
    }

    private companion object {
        /** 256 bits. The token guards a process holding a session cookie; do not shrink it. */
        const val TOKEN_BYTES = 32
        const val REPLACING_A_LINK =
            "Replacing {}, a symbolic link, with a new /watch token rather than reading or writing through it " +
                "(#387). Paste the new one into the extension; to keep the token elsewhere, point " +
                "tracker.watch.token-file at that file itself."
        val log = LoggerFactory.getLogger(WatchToken::class.java)!!
    }
}
