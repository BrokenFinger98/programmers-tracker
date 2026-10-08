package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.store.StateDirectory
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * The credential store pushes authenticate through, and how the server points git at it
 * **without writing into the record repository's own config** (#267).
 *
 * The file itself stays where #258 put it — `<records>/.ps/git-credentials`, owner-only, inside
 * the state directory the tracker's own commits leave out. A path alone was not enough to keep it
 * out of a commit (#360), so before every commit and push the token itself is searched for
 * ([stored] feeds that search). A failed push logged with git's own words cannot contain it: the
 * remote URL carries none.
 *
 * The *pointer* is the part that moved. It used to be `git config credential.helper` in the
 * record repository's `.git/config`, holding an absolute path — and inside a container that path
 * is `/records/…`, while the same `.git/config` is the user's working copy on a host where
 * `/records` does not exist. Measured on macOS: `git push` succeeded (the system's `osxkeychain`
 * answers first) and printed
 *
 * ```
 * fatal: unable to get credential storage lock in 1000 ms: No such file or directory
 * ```
 *
 * A success that reads as a failure is the same defect as a badge that cannot tell working from
 * broken (#147) — it teaches the reader to distrust a correct outcome. On a machine with no
 * other helper the repo-local entry is the only one, supplies nothing, and the host-side push
 * genuinely has no credential.
 *
 * So nothing is persisted anywhere the user shares. The server passes the helper on its own
 * invocations with `git -c`, and the repository it does that in stays exactly as the user left it.
 *
 * **The answer is the file's existence, never the token's.** The container's own config would not
 * survive a recreate while the file, being in the bind mount, does — and `compose.yaml` promises
 * *"You may delete this line after the first boot."* Gate the pointer on `GITHUB_TOKEN` and that
 * promise becomes false on the first recreate after the line is deleted.
 */
class PushCredential(private val root: Path) {
    /**
     * The store, whether or not it exists yet.
     *
     * Normalised as well as absolute: `toAbsolutePath` alone keeps every `..` it was handed, and
     * this string is read by a person the moment a push fails.
     */
    fun file(): Path = root.toAbsolutePath().normalize().resolve(FILE)

    /**
     * The `-c` prefix for one git invocation, or nothing when no credential was ever stored.
     *
     * Recomputed per call rather than answered once: [GithubRemote] writes the file during the
     * same boot that constructs [CommandLineGitSync], and a cached "no" would leave that boot
     * pushing without a credential.
     */
    fun gitConfig(): List<String> {
        val file = file()
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return emptyList()
        return listOf("-c", "credential.helper=store --file=$file")
    }

    /**
     * What the store holds, for the search that keeps it out of every commit and push (#360). Read
     * without following a link: only a regular file at this path is the credential, and anything
     * else there — a link a pull delivered — cannot be checked against, so it is [StoredCredential.Unreadable].
     */
    fun stored(): StoredCredential {
        val file = file()
        if (!Files.exists(file, NOFOLLOW_LINKS)) return StoredCredential.None
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return StoredCredential.Unreadable
        return runCatching { StoredCredential.of(readWithoutFollowing(file)) }.getOrDefault(StoredCredential.Unreadable)
    }

    private fun readWithoutFollowing(file: Path): String =
        Files.newInputStream(file, NOFOLLOW_LINKS).use { String(it.readAllBytes(), Charsets.UTF_8) }

    companion object {
        /** The store's own name, inside the state directory. */
        const val STORE = "git-credentials"

        /** Beside the raw frames and the timers, under the state directory (#126). */
        const val FILE = "${StateDirectory.NAME}/$STORE"
    }
}

/** What the credential store holds, as far as the content gate needs to know (#360). */
sealed interface StoredCredential {
    /** No store, or nothing in it: there is nothing to search for. */
    data object None : StoredCredential

    /** A store that is not a regular file, or cannot be read: nothing can be searched for. */
    data object Unreadable : StoredCredential

    /**
     * The fixed strings a leak would carry: each stored line as it is, and the secret in it, raw and
     * decoded. Never a prefix of it, and never the user name every GitHub token shares. Renders
     * masked, like every credential type here (dev rules §7.2).
     */
    class Patterns(private val values: List<String>) : StoredCredential {
        /** One pattern per line, for `git grep -F -f -`: they reach git on stdin, never in argv. */
        fun asInput(): String = values.joinToString("\n", postfix = "\n")

        override fun toString(): String = "Patterns(***)"
    }

    companion object {
        fun of(text: String): StoredCredential {
            val patterns = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap(::patternsOf).distinct()
            if (patterns.isEmpty()) return None
            return Patterns(patterns)
        }

        private fun patternsOf(line: String): List<String> {
            val url = runCatching { URI(line) }.getOrNull()
            return listOfNotNull(line, url?.rawUserInfo?.secret(), url?.userInfo?.secret()).filter { it.isNotEmpty() }
        }

        // The password after the first colon, or the whole user info when it has none.
        private fun String.secret(): String = substringAfter(':')
    }
}
