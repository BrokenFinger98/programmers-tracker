package com.brokenfinger.tracker.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What a commit of a scope would add, found without touching the index (#376): the `git add` the commit
 * stages with, run on a copy of the index in a directory of its own, and that copy compared with the tree the
 * commit adds to. A search of it that refuses leaves nothing staged (#360's M5).
 *
 * The same `add` rather than a reading of the files: it applies the clean filters, stores a link as the path
 * it holds, and leaves out what git ignores, as the commit's own `add` does. And a copy of the index rather
 * than one read from HEAD's tree: the copy keeps git's record of each file's size and times, so `add` reads
 * only the files that changed — 35 ms against 565 ms for 5,000 files with one new (measured, git 2.48.1).
 *
 * [git] runs one git call with variables for it alone; [directory] makes the directory the copy is kept in.
 */
internal class StagingPreview(
    private val root: Path,
    private val git: (List<String>, Map<String, String>) -> GitResult,
    private val directory: () -> Path = { Files.createTempDirectory("git-index-") },
) {
    /** What staging [scope] would add to [base], a tree; or git's own answer when it could not stage or compare. */
    fun of(scope: List<String>, base: String): Preview {
        val index = git(listOf("rev-parse", "--git-path", "index"), emptyMap())
        if (!index.succeeded()) return Preview.Failed(index)
        val scratch = directory()
        try {
            return staged(copied(root.resolve(index.stdout.trim()), scratch.resolve(INDEX)), scope, base)
        } finally {
            cleared(scratch)
        }
    }

    /**
     * The index as it is, with its own modification time: git re-reads a file whose entry is as new as the
     * index — a "racily clean" one, which a change within the same tick leaves looking unchanged — and a copy
     * stamped now would make every entry look settled. A repository with nothing staged yet has no index, and
     * git starts the copy empty.
     */
    private fun copied(index: Path, copy: Path): Path {
        if (Files.exists(index)) Files.copy(index, copy, StandardCopyOption.COPY_ATTRIBUTES)
        return copy
    }

    private fun staged(copy: Path, scope: List<String>, base: String): Preview {
        val variables = mapOf(INDEX_FILE to copy.toString())
        val added = git(listOf("add", "--all", "--") + scope, variables)
        if (!added.succeeded()) return Preview.Failed(added)
        val changed = git(listOf("diff-index", "--cached", "-z", "--no-renames", base, "--") + scope, variables)
        if (!changed.succeeded()) return Preview.Failed(changed)
        return Introduced.ofReceived(changed.stdout, base) ?: Preview.Failed(changed)
    }

    // The copy, a lock a git cut short may leave beside it, and the directory: nothing else is written there.
    private fun cleared(scratch: Path) {
        listOf(scratch.resolve(INDEX), scratch.resolve("$INDEX.lock"), scratch).forEach(::discarded)
    }

    private fun discarded(path: Path) {
        runCatching { Files.deleteIfExists(path) }.onFailure { path.toFile().deleteOnExit() }
    }

    private companion object {
        const val INDEX = "index"

        /** The variable that points git at another index: [GitProcess] removes the one the server inherited. */
        const val INDEX_FILE = "GIT_INDEX_FILE"
    }
}

/** What a preview of staging found: what the commit would add ([Introduced]), or why git could not say. */
internal sealed interface Preview {
    /** Git could not stage the scope or compare it: its answer, so the reason is logged in its own words. */
    class Failed(val answer: GitResult) : Preview
}
