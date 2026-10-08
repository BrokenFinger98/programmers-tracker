package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.adapter.store.TrackedState
import java.nio.file.Files
import java.nio.file.Path

/**
 * The git adapter's answer to [TrackedState]: whether git tracks any entry that is the state directory
 * or lies under it — the entry `.ps` itself or anything below it, under any name that comes to the same
 * directory (#360).
 *
 * A pull can deliver a tracked file there, and the state directory is ignored, not empty: the server
 * writes the push credential into it. Over a tracked path that write is a change git reports, and any
 * `commit -a` publishes it; a one-letter store also turned every search for the credential into a
 * false alarm. So git runs, and the credential is stored, only while git tracks nothing there.
 *
 * **Asked of every entry, not through a pathspec.** `:(icase)` folds ASCII alone, and APFS folds more:
 * a pulled `.pſ/x` (`.p` and U+017F) lands inside the real `.ps`, while the index keeps `.pſ/x`, and
 * the pathspec found nothing (U1, the review of ea1357c — measured on the host and in the image). So
 * every path the index lists is judged by its first segment: under the state directory when it is
 * `.ps` in any case, Unicode folds included, or when the filesystem resolves it to the same directory
 * as `.ps` — a fold no case rule knows, a short name, a link.
 *
 * Read from the index, which is what `commit -a` commits from. Null when git cannot be asked — a
 * directory that is no repository, a git that did not finish — and the callers decide what unknown
 * means for them.
 */
class TrackedStateEntries(private val root: Path, environment: Map<String, String> = System.getenv()) :
    TrackedState {
    private val process = GitProcess(root, environment)

    override fun any(): Boolean? = runCatching { listed() }.getOrNull()

    private fun listed(): Boolean? {
        val result = process.run(listOf("git", "ls-files", "-z"))
        if (!result.succeeded()) return null
        return firstSegments(result.stdout).any { isStateDirectory(it) }
    }

    private fun firstSegments(listing: String): Set<String> =
        listing.split(NUL).filter { it.isNotEmpty() }.map { it.substringBefore('/') }.toSet()

    // `equals(ignoreCase = true)` folds as Java's `equalsIgnoreCase` does, so `.pſ` is `.ps` too.
    private fun isStateDirectory(segment: String): Boolean =
        segment.equals(StateDirectory.NAME, ignoreCase = true) || resolvesToState(segment)

    private fun resolvesToState(segment: String): Boolean = runCatching {
        val entry = root.resolve(segment)
        val state = root.resolve(StateDirectory.NAME)
        Files.exists(entry) && Files.exists(state) && Files.isSameFile(entry, state)
    }.getOrDefault(false)

    private companion object {
        const val NUL = '\u0000'
    }
}
