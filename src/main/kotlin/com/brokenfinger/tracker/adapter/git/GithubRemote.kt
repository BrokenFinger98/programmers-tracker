package com.brokenfinger.tracker.adapter.git

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A GitHub token in, a private repository wired as `origin` out (#258).
 *
 * The token was the owner's call, made with the trade-off on the table: it is the one secret
 * that replaces the whole SSH path — key generation, deploy-key registration, a compose
 * override — with a single `.env` line. What contains it:
 *
 * - **`private` is hardcoded in the request and verified in the response.** A repository that
 *   comes back public for any reason is *not wired*: pushing solving history to a public
 *   repository is the worst outcome this tool can produce, so an API surprise fails the wiring,
 *   never the privacy.
 * - **The token never reaches a log or a remote URL.** [GithubToken] renders masked, and pushes
 *   authenticate through a credential store at `.ps/git-credentials` — owner-only, inside the
 *   directory every record repository already gitignores — so a failed push logged with git's
 *   own words cannot contain it.
 * - **An existing `origin` of any kind is never rewired.** SSH users, other hosts, other names:
 *   whatever is wired stays wired. The credential store *is* still refreshed — that is the
 *   migration path from SSH (point the remote at https, boot) and the rotation path (edit
 *   `.env`, boot) — and it is inert beside an SSH remote, since the helper answers only for
 *   `https://github.com`.
 * - After the first boot the `.env` line may be removed; the credential store carries pushes
 *   from then on. Revoking the token stops pushes until a new one is stored.
 *
 * 422 on creation means the name exists — a rerun after a partial first boot, or a repository
 * the user created themselves. It is looked up and wired instead, behind the same private check.
 */
class GithubRemote(
    private val recordRoot: Path,
    private val token: GithubToken?,
    private val apiBase: String = "https://api.github.com",
) {
    fun ensure() {
        runCatching { wireUnlessWired() }.onFailure { warn(it) }
    }

    private fun wireUnlessWired() {
        if (token == null) return
        // Always, not only when wiring: this is how an SSH setup migrates to the token (change
        // the remote URL, boot) and how a rotated token in .env takes effect. The helper only
        // ever answers for https://github.com, so an SSH remote is unaffected by its existence.
        storeCredential()
        if (hasOrigin()) {
            logger.info("origin already exists — credential refreshed, nothing rewired")
            return
        }
        val name = recordRoot.fileName.toString()
        val cloneUrl = privateRepository(name) ?: return
        git("remote", "add", "origin", cloneUrl)
        // `git push` with no upstream refuses on a fresh clone; `current` makes the first push —
        // the startup backup's — work without one, and every later push is unaffected.
        git("config", "push.default", "current")
        logger.info("Wired origin to a private GitHub repository ({}) — records will push there", name)
    }

    /** The clone URL of a repository that is verifiably private, or null and a loud log. */
    private fun privateRepository(name: String): String? {
        val created = api("POST", "/user/repos", """{"name":"$name","private":true}""")
        val answer = when (created.statusCode()) {
            201 -> created.body()
            // The name exists: converge on it — but only through the same private gate.
            422 -> api("GET", "/repos/${login()}/$name", null).takeIf { it.statusCode() == 200 }?.body()
            else -> null
        }
        if (answer == null) {
            logger.warn("GitHub did not provide a repository (HTTP {}) — origin stays unwired", created.statusCode())
            return null
        }
        return verifiedPrivate(answer)
    }

    private fun verifiedPrivate(body: String): String? {
        val repo = Json.parseToJsonElement(body).jsonObject
        val isPrivate = repo["private"]?.jsonPrimitive?.boolean == true
        if (!isPrivate) {
            logger.error(
                "GitHub answered with a repository that is NOT private — refusing to wire it. " +
                    "Solving records must never push to a public repository.",
            )
            return null
        }
        return repo["clone_url"]?.jsonPrimitive?.content
    }

    private fun login(): String = Json.parseToJsonElement(api("GET", "/user", null).body()).jsonObject
        .getValue("login").jsonPrimitive.content

    /**
     * The credential store pushes authenticate through — rewritten on every wiring so a rotated
     * token in `.env` takes effect, owner-only like the watch token, and inside `.ps/` so the
     * gitignore the server itself maintains keeps it out of every commit.
     */
    private fun storeCredential() {
        val file = recordRoot.resolve(CREDENTIALS)
        Files.createDirectories(file.parent)
        runCatching {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        }
        Files.writeString(file, "https://x-access-token:${token!!.raw()}@github.com\n")
        runCatching { Files.setPosixFilePermissions(file, OWNER_ONLY) }
        git("config", "credential.helper", "store --file=${file.toAbsolutePath()}")
    }

    private fun hasOrigin(): Boolean = git("remote").lines().any { it.trim() == "origin" }

    private fun api(method: String, path: String, body: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(apiBase + path))
            .header("Authorization", "Bearer ${token!!.raw()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(20))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(recordRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        return output
    }

    // The token itself must not appear here, and GithubToken.toString() makes sure a lazy
    // interpolation could not leak it either.
    private fun warn(cause: Throwable) {
        logger.warn(
            "Could not wire the GitHub remote ({}). Records stay local; the daily backup will " +
                "say so until a remote exists.",
            cause.javaClass.simpleName,
        )
    }

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private companion object {
        const val CREDENTIALS = ".ps/git-credentials"

        val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

        val logger = LoggerFactory.getLogger(GithubRemote::class.java)
    }
}

/**
 * The GitHub token, shaped exactly like [com.brokenfinger.tracker.protocol.SessionCookie] and
 * for the same reason: it renders masked, so no log line, exception message or debug dump can
 * carry it (dev rules §7.2).
 */
@JvmInline
value class GithubToken(private val value: String) {
    fun raw(): String = value

    override fun toString(): String = "GithubToken(***)"
}
