package com.brokenfinger.tracker.adapter.store

import java.nio.file.Files
import java.nio.file.Path

/**
 * How a file under `problems/` is read when what it holds can leave this machine (#354): from a
 * regular file whose real path lies under the records repository's own `problems/` directory, or not
 * at all.
 *
 * **The threat.** The repository root holds, beside `problems/`, what must never leave: the push token
 * in `.ps/git-credentials` (`https://x-access-token:<token>@github.com`), the `/watch` token and the
 * original frames. What is read under `problems/` does leave — a statement and kept code through MCP, a
 * diff into `log/submissions.jsonl`, which MCP serves and git pushes, the statement into the problem
 * page and example values into a runner, both committed and pushed. Git stores symbolic links, so a
 * link under `problems/` can arrive with a clone or a pull of the records repository, not only from
 * someone with a shell here. A `statement.md` linked to `../../.ps/git-credentials` handed the token to
 * `get_problem` (reproduced in #353's review), and a lexical check cannot see it: the path a reader is
 * handed still reads `problems/...`.
 *
 * **The bound.** The candidate is resolved to its real path, links and all, and accepted only when that
 * lies under the real root with `problems` appended by name. A link that stays inside reads as the file
 * it names; one that leads out reads as nothing. Only the root is resolved, because it may sit behind a
 * link (macOS's `/var` is one, a `~/ps-records` link another). `problems` never is: resolving a linked
 * `problems` would carry the bound to wherever the link leads. Then a regular file only, because a FIFO
 * would block the calling thread for a writer that never comes.
 *
 * **Never throws.** A dangling link, a missing file, a directory or an unreadable file is absent, the
 * posture every reader here already takes. [RecordLayout] stays lexical; this is the half that looks
 * at the filesystem.
 */
class ProblemFiles(private val layout: RecordLayout) {
    /** [Files.readAllBytes], bounded — for a reader that decodes leniently itself. */
    fun readAllBytes(candidate: Path): ByteArray? = read(candidate) { Files.readAllBytes(it) }

    /** [Files.readString], bounded, and as strict as it is: bytes that are not UTF-8 are absent, not replaced. */
    fun readString(candidate: Path): String? = read(candidate) { Files.readString(it) }

    private fun <T : Any> read(candidate: Path, reading: (Path) -> T): T? =
        runCatching { containedRegularFile(candidate)?.let(reading) }.getOrNull()

    // The real path is what gets opened, so the file that was checked is the file that is read.
    private fun containedRegularFile(candidate: Path): Path? {
        val real = candidate.toRealPath()
        if (!real.startsWith(realProblemsDirectory())) return null
        return real.takeIf { Files.isRegularFile(it) }
    }

    private fun realProblemsDirectory(): Path {
        val problems = layout.problemsDirectory()
        return problems.parent.toRealPath().resolve(problems.fileName)
    }
}
