package com.brokenfinger.tracker.adapter.web

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
 * It stays beside the tool rather than joining the state that moved into the record
 * repository (#126): that state describes the records and travels with them, while this is a
 * credential and the record repository is pushed.
 */
@Component
class WatchToken(
    @Value("\${tracker.watch.token:}") configured: String,
    @Value("\${tracker.watch.token-file:.ps/watch-token}") tokenFile: String,
) {
    private val expected: ByteArray = resolve(configured.trim(), Path.of(tokenFile)).toByteArray(UTF_8)

    /** @throws UnauthorizedWatchException when the presented value is absent or does not match. */
    fun verify(presented: String?) {
        val candidate = presented?.trim().orEmpty()
        if (candidate.isEmpty()) throw UnauthorizedWatchException()
        if (!MessageDigest.isEqual(expected, candidate.toByteArray(UTF_8))) throw UnauthorizedWatchException()
    }

    /** Masked like SessionCookie — a credential must not be printable by accident (dev rules §7.2). */
    override fun toString(): String = "WatchToken(***)"

    private fun resolve(configured: String, file: Path): String {
        if (configured.isNotBlank()) return configured
        return persistedIn(file) ?: generateInto(file)
    }

    private fun persistedIn(file: Path): String? = runCatching { file.readText() }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun generateInto(file: Path): String {
        val generated = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
        store(generated, file)
        return generated
    }

    private fun store(generated: String, file: Path) {
        runCatching { writeOwnerOnly(generated, file) }
            .onSuccess { log.info("Generated a local /watch token at {} — paste it into the extension.", file) }
            .onFailure { log.warn("Could not persist the generated /watch token at {}: {}", file, it.message) }
    }

    private fun writeOwnerOnly(generated: String, file: Path) {
        file.createParentDirectories()
        file.writeText(generated)
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    private companion object {
        /** 256 bits. The token guards a process holding a session cookie; do not shrink it. */
        const val TOKEN_BYTES = 32
        val log = LoggerFactory.getLogger(WatchToken::class.java)!!
    }
}
