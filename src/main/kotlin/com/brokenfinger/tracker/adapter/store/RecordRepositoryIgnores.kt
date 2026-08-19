package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guarantees the record repository ignores what is not a record.
 *
 * Reconciliation is `git add --all`, so anything sitting in the vault is committed under a
 * message that says records. Two directories are in the vault and are not records:
 *
 * - **`.ps/`, the tracker's own state** (design §5.1). It holds frames still being captured,
 *   per-problem timers and the raw-run queue — and `.ps/raw/recorded/` keeps a session per
 *   **run**, pressed dozens of times while writing one solution. Committing them is the
 *   inflation [[decisions/2026-08-08-run-raw-sessions]] weighed and rejected. Submits are not
 *   the concern: their frames are copied into `attempts/00N.raw.jsonl` and the source is
 *   discarded, so `recorded/` holds runs and only runs — pinned by `RecordWriterTest` from both
 *   sides, because this comment once said the opposite (#128).
 * - **`.obsidian/`, the vault's editor state** (#234). Which panes are open, where the cursor
 *   was, how the graph is zoomed. On the owner's repository four commits carried it and one —
 *   the 23:00 backup — carried nothing else, under `chore: reconcile uncommitted records`.
 *
 * **Whole directories, never a list of files inside them.** The rule used to name `.ps/session`,
 * `.ps/cookies*` and `.ps/catalog.json` one at a time, so every state file added afterwards was
 * committed by default and one of them was a credential (#122). A list that has to be extended
 * whenever the other tool learns to write something is a list that will be wrong; Obsidian adds
 * files of its own on its own schedule.
 *
 * **Why the template is not enough.** `template/ps-records/.gitignore` carries both rules, but a
 * template is copied once, when the repository is created. Their `.gitignore` belongs to the
 * user — there is no version of it we can overwrite — so a missing line is added and nothing
 * else is touched. **A rule already in the file is never re-added**, whichever way they spelled
 * it, and adding the rule does not untrack what is already tracked: `git rm --cached` is the
 * user's to run, on their own history.
 *
 * Runs on every boot and appends each rule at most once. Failure is logged, never thrown: a
 * grading Programmers has already broadcast cannot be replayed (protocol §11), and losing one to
 * a `.gitignore` that would not write is the wrong trade in every direction.
 */
class RecordRepositoryIgnores(private val recordRoot: Path) {
    fun ensure() {
        runCatching { addMissingRules() }.onFailure { warn(it) }
    }

    private fun addMissingRules() {
        val file = recordRoot.resolve(GITIGNORE)
        var text = read(file) ?: ""
        val added = RULES.filterNot { text.alreadyIgnores(it.rule) }
        if (added.isEmpty()) return
        added.forEach { text += it.appendedTo(text) }
        Files.writeString(file, text)
        logger.info("Added {} to {} — what is not a record must not be committed", added.map { it.rule }, file)
    }

    private fun read(file: Path): String? = runCatching { Files.readString(file) }.getOrNull()

    // Both spellings mean the same thing to git, and the user may have written either. Only the
    // exact rule counts: `.ps/session` names one file and ignores nothing else.
    /**
     * The rule itself, however they spelled the trailing slash — **or a directory above it**.
     *
     * A reader who wrote `.obsidian` meant the whole directory, and appending
     * `.obsidian/workspace.json` underneath would be the server arguing with a broader decision
     * they already made. Their file is theirs; a missing line is added and nothing else (#306).
     */
    private fun String.alreadyIgnores(rule: String): Boolean {
        val spellings = ancestorsOf(rule).flatMap { listOf(it, "$it/") }.toSet()
        return lineSequence().map { it.trim() }.any { it in spellings }
    }

    /** `.obsidian/workspace.json` → itself and `.obsidian`; a rule with no slash → just itself. */
    private fun ancestorsOf(rule: String): List<String> {
        val trimmed = rule.removeSuffix("/")
        val parts = trimmed.split("/")
        return parts.indices.map { parts.take(it + 1).joinToString("/") }
    }

    /** One directory, and the sentence that stops a later tidy-up removing it as leftovers. */
    private data class IgnoreRule(val rule: String, val because: String) {
        fun appendedTo(existing: String): String =
            (if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n") + "\n$because$rule\n"
    }

    private fun warn(cause: Throwable) {
        logger.warn(
            "Could not ensure {} ignores {} ({}). Add the lines by hand — otherwise the server's " +
                "state and your editor's are committed to your records repository.",
            recordRoot,
            RULES.map { it.rule },
            cause.javaClass.simpleName,
        )
    }

    companion object {
        private const val GITIGNORE = ".gitignore"

        private val RULES = listOf(
            IgnoreRule(
                rule = ".ps/",
                because = "# The tracker's working state, added by the server itself. It lives here so that it sits\n" +
                    "# beside the records it describes, and it is not records: frames still being captured,\n" +
                    "# per-problem timers, when the last push succeeded. `.ps/raw/recorded/` keeps one file\n" +
                    "# per code run, and a run gets pressed dozens of times while solving one problem —\n" +
                    "# committing them would bury your solving history in its own scratch work.\n",
            ),
            IgnoreRule(
                rule = ".programmers-tracker.lock",
                because = "# The tracker's exclusive lock. It normally lives inside .git/, where git cannot\n" +
                    "# see it; this covers the one case where it cannot — a run from before `git init`.\n",
            ),
            IgnoreRule(
                rule = ".DS_Store",
                because = "# Finder noise, which `git add --all` would otherwise commit as if it were records.\n",
            ),
            IgnoreRule(
                rule = ".obsidian/workspace.json",
                because = "# Obsidian's window layout — which panes are open, where they are scrolled. It changes\n" +
                    "# because the vault was *opened*, and `git add --all` cannot tell that from a record: one\n" +
                    "# backup commit carried nothing else, under a message about records (#234).\n" +
                    "#\n" +
                    "# Only this file. `graph.json` beside it holds the colour groups you built, and the rest\n" +
                    "# are settings — versioning them is why a vault cloned onto another machine opens\n" +
                    "# configured rather than blank, which is the same promise the records themselves make\n" +
                    "# (#306, narrowing the rule that took the whole directory).\n",
            ),
            IgnoreRule(
                rule = ".obsidian/workspace-mobile.json",
                because = "# The same file, from the phone.\n",
            ),
            IgnoreRule(
                rule = ".idea/",
                because = "# The other editor's state, here for the same reason as the line above (#304). This is a\n" +
                    "# Kotlin project, so a vault opened in IntelliJ is not an edge case — and `git add --all`\n" +
                    "# cannot tell a window layout from a record any better than it could tell a graph zoom.\n" +
                    "# Delete this line if you would rather version it; nothing else depends on it.\n",
            ),
            IgnoreRule(
                rule = "*.iml",
                because = "# The rest of that editor's state, which the line above cannot reach: IntelliJ writes\n" +
                    "# <project>.iml at the ROOT, not inside .idea/. It lists every problem directory it has\n" +
                    "# indexed, so it outlives the records it names — measured on a repository whose history\n" +
                    "# had been cleared, still publishing four problem titles, two of them already deleted\n" +
                    "# (#323). Same escape hatch as the line above.\n",
            ),
        )

        private val logger = LoggerFactory.getLogger(RecordRepositoryIgnores::class.java)
    }
}
