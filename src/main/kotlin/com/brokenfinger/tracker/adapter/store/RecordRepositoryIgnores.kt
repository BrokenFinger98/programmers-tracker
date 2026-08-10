package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guarantees the record repository ignores the tracker's state directory.
 *
 * State lives at `<record-repo>/.ps/` (design §5.1) and reconciliation is `git add --all`, so
 * a repository that does not ignore it commits the state. That is not merely untidy —
 * `.ps/raw/recorded/` is the one directory [[decisions/2026-08-08-run-raw-sessions]] exists
 * to keep out of a commit. It holds a session per **run**, and a run "gets pressed dozens of
 * times while writing code": committing them puts dozens of files per problem into the
 * user's repository, which is the inflation design §5.1 exists to prevent. That ADR weighed
 * putting them in the repository and rejected it; ignoring the directory is what carries the
 * decision now that the state sits inside the repository at all.
 *
 * Submits are not the concern. Their frames are copied into `attempts/00N.raw.jsonl` and the
 * source is discarded, so `recorded/` holds runs and only runs — pinned by `RecordWriterTest`
 * from both sides, because this comment once said the opposite (#128).
 *
 * **Why the template is not enough.** `template/ps-records/.gitignore` carries the rule, but
 * a template is copied once, when the repository is created. Repositories made before #122
 * name `.ps/session` and `.ps/catalog.json` one at a time and ignore none of the rest, and
 * their `.gitignore` belongs to the user — there is no version of it we can overwrite. So the
 * one line is added, and nothing else is touched.
 *
 * Runs on every boot and appends at most once. Failure is logged, never thrown: a grading
 * Programmers has already broadcast cannot be replayed (protocol §11), and losing one to a
 * `.gitignore` that would not write is the wrong trade in every direction.
 */
class RecordRepositoryIgnores(private val recordRoot: Path) {
    fun ensure() {
        runCatching { addRuleUnlessPresent() }.onFailure { warn(it) }
    }

    private fun addRuleUnlessPresent() {
        val file = recordRoot.resolve(GITIGNORE)
        val existing = read(file)
        if (existing != null && ignoresState(existing)) return
        Files.writeString(file, (existing ?: "") + preamble(existing) + RULE + "\n")
        logger.info("Added `{}` to {} — the tracker's state must not be committed", RULE, file)
    }

    private fun read(file: Path): String? = runCatching { Files.readString(file) }.getOrNull()

    // Both spellings mean the same thing to git, and the user may have written either. Only
    // the exact rule counts: `.ps/session` names one file and ignores nothing else.
    private fun ignoresState(text: String): Boolean =
        text.lineSequence().map { it.trim() }.any { it == RULE || it == RULE.removeSuffix("/") }

    // A rule with no explanation invites a later tidy-up to remove it as leftovers.
    private fun preamble(existing: String?): String =
        (if (existing.isNullOrEmpty() || existing.endsWith("\n")) "" else "\n") +
            "\n# The tracker's working state, added by the server itself. It lives here so that it sits\n" +
            "# beside the records it describes, and it is not records: frames still being captured,\n" +
            "# per-problem timers, when the last push succeeded. `.ps/raw/recorded/` keeps one file\n" +
            "# per code run, and a run gets pressed dozens of times while solving one problem —\n" +
            "# committing them would bury your solving history in its own scratch work.\n"

    private fun warn(cause: Throwable) {
        logger.warn(
            "Could not ensure {} ignores `{}` ({}). Add the line by hand — otherwise the server's " +
                "state is committed to your records repository.",
            recordRoot,
            RULE,
            cause.javaClass.simpleName,
        )
    }

    companion object {
        private const val GITIGNORE = ".gitignore"
        private const val RULE = ".ps/"

        private val logger = LoggerFactory.getLogger(RecordRepositoryIgnores::class.java)
    }
}
