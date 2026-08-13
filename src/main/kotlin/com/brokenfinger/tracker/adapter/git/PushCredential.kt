package com.brokenfinger.tracker.adapter.git

import java.nio.file.Files
import java.nio.file.Path

/**
 * The credential store pushes authenticate through, and how the server points git at it
 * **without writing into the record repository's own config** (#267).
 *
 * The file itself stays where #258 put it — `<records>/.ps/git-credentials`, owner-only, inside
 * the directory the server already gitignores, so it can never be committed and a failed push
 * logged with git's own words can never contain the token.
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
        if (!Files.exists(file)) return emptyList()
        return listOf("-c", "credential.helper=store --file=$file")
    }

    companion object {
        /** Beside the raw frames and the timers, under the state directory (#126). */
        const val FILE = ".ps/git-credentials"
    }
}
