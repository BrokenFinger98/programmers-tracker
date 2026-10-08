package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.adapter.store.TrackedHistory
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
 *
 * **And what git has ever tracked there** ([pathsEverTracked], #377), read from every ref's history and every
 * reflog: once untracked, a raw session a pull delivered is a file like the tracker's own, and only the history
 * still names it. Judged by the same first segment.
 */
class TrackedStateEntries(private val root: Path, environment: Map<String, String> = System.getenv()) :
    TrackedState {
    private val process = GitProcess(root, environment)

    override fun tracksAnything(): Boolean? = runCatching { listed() }.getOrNull()

    /**
     * Every path a commit has added, changed or removed below the state directory, by the part below it, for every
     * commit a ref or a reflog entry reaches (#377): a pull can deliver a raw session there, and untracking it
     * leaves the file behind. One `git log` ([HISTORY]); its first segments are judged as [tracksAnything]
     * judges the index. Unanswered, with why, when git fails, times out or cannot be started.
     */
    override fun pathsEverTracked(): TrackedHistory =
        runCatching { inHistory() }.getOrElse { TrackedHistory.Unanswered(it.javaClass.simpleName) }

    private fun listed(): Boolean? {
        val result = process.run(listOf("git", "ls-files", "-z"))
        if (!result.succeeded()) return null
        return firstSegments(pathsIn(result.stdout)).any { isStateDirectory(it) }
    }

    private fun inHistory(): TrackedHistory {
        val result = process.run(HISTORY)
        if (!result.succeeded()) return TrackedHistory.Unanswered(reasonOf(result))
        return TrackedHistory.Known(belowStateDirectory(pathsIn(result.stdout)))
    }

    // Each first segment is judged once: the history names the same few thousands of times.
    private fun belowStateDirectory(paths: List<String>): Set<String> {
        val state = firstSegments(paths).filter { isStateDirectory(it) }.toSet()
        return paths.filter { '/' in it && it.substringBefore('/') in state }.map { it.substringAfter('/') }.toSet()
    }

    private fun pathsIn(listing: String): List<String> = listing.split(NUL).filter { it.isNotEmpty() }

    private fun firstSegments(paths: List<String>): Set<String> = paths.map { it.substringBefore('/') }.toSet()

    // `equals(ignoreCase = true)` folds as Java's `equalsIgnoreCase` does, so `.pſ` is `.ps` too.
    private fun isStateDirectory(segment: String): Boolean =
        segment.equals(StateDirectory.NAME, ignoreCase = true) || resolvesToState(segment)

    private fun resolvesToState(segment: String): Boolean = runCatching {
        val entry = root.resolve(segment)
        val state = root.resolve(StateDirectory.NAME)
        Files.exists(entry) && Files.exists(state) && Files.isSameFile(entry, state)
    }.getOrDefault(false)

    internal companion object {
        private const val NUL = '\u0000'

        /**
         * Every ref and every reflog entry: a reset and a force-push, or a force-push and a plain `pull --rebase`,
         * leave the commit that delivered a session in the reflogs alone (the review of PR #395, measured). A merge
         * is compared with each parent, so a path a merge alone added is named; the root commit against nothing; no
         * rename detection, so a moved path is named at both ends; and no colour or signature among the paths. An
         * unborn repository answers nothing, and exit 0. What no reflog holds any more — an expired entry, a
         * rewritten history — is not named, which is why the owner deletes what git put there first.
         */
        private val HISTORY = listOf(
            "git", "log", "--all", "--reflog", "-m", "--root", "--no-renames", "--no-color", "--no-show-signature",
            "--name-only", "-z", "--format=",
        )

        /** How much of git's own first line a reason keeps. */
        const val REASON_LENGTH = 200

        private val DID_NOT_FINISH = "git log did not finish within ${GitProcess.TIMEOUT.seconds} s"

        /**
         * Why the history question failed: how git ended, and its own first line when it said one — why, never what
         * any file holds (the review of PR #395). Built from the result alone, so its bound is tested whatever a git
         * version says: 2.48.1 named a missing git directory in full, and CI's gits printed `(null)` (PR #401).
         */
        fun reasonOf(result: GitResult): String {
            if (result.code == GitProcess.TIMED_OUT) return DID_NOT_FINISH
            val said = result.stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return listOfNotNull("git log exited ${result.code}", said?.take(REASON_LENGTH)).joinToString(": ")
        }
    }
}
