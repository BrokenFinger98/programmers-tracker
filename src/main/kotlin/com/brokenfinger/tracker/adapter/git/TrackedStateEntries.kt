package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.adapter.store.TrackedState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
class TrackedStateEntries(private val root: Path) : TrackedState {
    override fun any(): Boolean? = runCatching { listed() }.getOrNull()

    private fun listed(): Boolean? {
        val output = Files.createTempFile("git-", ".out")
        try {
            if (exitCodeOf(output) != 0) return null
            return Files.size(output) > 0
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun exitCodeOf(output: Path): Int {
        val process = ProcessBuilder(listOf("git", "ls-files", "-z", "--") + PATHSPECS)
            .directory(root.toFile())
            .redirectOutput(output.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.close()
        if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return process.exitValue()
        process.destroyForcibly()
        return TIMED_OUT
    }

    private companion object {
        val PATHSPECS = listOf(":(glob,icase)${StateDirectory.GLOB}", ":(glob,icase)${StateDirectory.GLOB}/**")
        const val TIMEOUT_SECONDS = 60L
        const val TIMED_OUT = -1
    }
}
