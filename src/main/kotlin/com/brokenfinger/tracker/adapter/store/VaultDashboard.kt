package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Seeds `dashboard.base` into the record repository — the vault's tables, as an Obsidian Base
 * (#254).
 *
 * ### Seeded, not generated
 *
 * The tag notes and each problem's README are **derived data**: rewritten whole on every pass,
 * because a stale copy of the records is worse than no copy. A `.base` file is not data. It is a
 * *query* Obsidian evaluates against frontmatter, so it cannot go stale — and the moment the
 * reader adds a column or changes a sort, it is theirs.
 *
 * So this writes **only when the file is absent** and never again. A server that rewrote it would
 * silently undo the reader's edits on the next restart, and nothing would say why their view kept
 * resetting. Same posture as [RecordRepositoryIgnores]: add what is missing, touch nothing else.
 *
 * ### Why the server and not the template
 *
 * `template/ps-records/` is copied once, when a repository is created, so every repository that
 * already exists would never receive this — the staleness that reached the vault README twice on
 * 2026-08-13. The Docker image also ships `src` and not `template/`, so a running container
 * cannot read a template file at all. One copy, on the classpath, written by whoever is running.
 *
 * Failure is logged, never thrown. A grading Programmers has broadcast cannot be replayed
 * (protocol §11), and losing one to a dashboard file would be the wrong trade in every direction.
 */
class VaultDashboard(private val recordRoot: Path) {
    fun ensure() {
        SEEDS.forEach { seed -> runCatching { writeUnlessPresent(seed) }.onFailure { warn(it) } }
    }

    private fun writeUnlessPresent(seed: String) {
        val file = recordRoot.resolve(seed)
        if (Files.exists(file)) return
        Files.createDirectories(recordRoot)
        Files.writeString(file, shipped(seed))
        logger.info("Wrote {} — seeded once; it is yours to edit from here", file)
    }

    // Read through the classloader rather than a path: inside the jar there is no file.
    private fun shipped(seed: String): String =
        checkNotNull(javaClass.getResourceAsStream("/vault/$seed")) { "/vault/$seed is missing from the jar" }
            .bufferedReader()
            .use { it.readText() }

    private fun warn(cause: Throwable) {
        logger.warn(
            "Could not seed a vault file into {} ({}). The vault works without it.",
            recordRoot,
            cause.javaClass.simpleName,
        )
    }

    private companion object {
        /**
         * Everything the vault starts with and the reader then owns (#255, #258). The README is
         * here — and not regenerated — because a user who rewrote their vault's front page must
         * keep it; the one who deleted it gets a fresh copy on the next boot, which is the rarer
         * intent and the recoverable mistake.
         */
        val SEEDS = listOf("dashboard.base", "README.md", "README.ko.md")

        val logger = LoggerFactory.getLogger(VaultDashboard::class.java)
    }
}
