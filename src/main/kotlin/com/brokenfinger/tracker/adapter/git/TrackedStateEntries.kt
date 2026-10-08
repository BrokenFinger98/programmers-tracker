package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.adapter.store.TrackedState
import java.nio.file.Path

/**
 * The git adapter's answer to [TrackedState]: whether `git ls-files` lists anything under the state
 * directory — the entry `.ps` itself or anything below it, in any case (#360).
 *
 * A pull can deliver a tracked file there, and the state directory is ignored, not empty: the server
 * writes the push credential into it. Over a tracked path that write is a change git reports, and any
 * `commit -a` publishes it; a one-letter store also turned every search for the credential into a
 * false alarm. So git runs, and the credential is stored, only while git tracks nothing there.
 *
 * Read from the index, which is what `commit -a` commits from. Null when git cannot be asked — a
 * directory that is no repository, a git that did not finish — and the callers decide what unknown
 * means for them.
 */
class TrackedStateEntries(root: Path, environment: Map<String, String> = System.getenv()) : TrackedState {
    private val process = GitProcess(root, environment)

    override fun any(): Boolean? = runCatching { listed() }.getOrNull()

    private fun listed(): Boolean? {
        val result = process.run(listOf("git", "ls-files", "-z", "--") + PATHSPECS)
        if (!result.succeeded()) return null
        return result.stdout.isNotEmpty()
    }

    private companion object {
        val PATHSPECS = listOf(":(glob,icase)${StateDirectory.GLOB}", ":(glob,icase)${StateDirectory.GLOB}/**")
    }
}
