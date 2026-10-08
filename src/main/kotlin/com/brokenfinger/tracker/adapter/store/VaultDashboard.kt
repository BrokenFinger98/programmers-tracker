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
 * So this never rewrites a file the reader has touched. A server that did would silently undo
 * their edits on the next restart, and nothing would say why their view kept resetting. Same
 * posture as [RecordRepositoryIgnores]: add what is missing, touch nothing else.
 *
 * ### The third state: untouched, and stale
 *
 * Absent and edited were the only two cases considered, which left **a file nobody has touched**
 * frozen at whatever shipped the day the vault was created. Twice on 2026-08-13 an improvement
 * reached the shipped seed and the one existing vault only because somebody edited it by hand
 * (#300).
 *
 * [SeedLedger] records the bytes written, so an untouched file can be recognised and replaced
 * while an edited one — a single character different — is left alone forever. A vault seeded
 * before the ledger existed has no record and counts as edited: absence is not permission.
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
 *
 * ### A link where a seed should be
 *
 * Left alone, and said (#361). A pull can deliver one, and following it the refresh overwrote
 * whatever it led to when the ledger recorded that file's bytes, while a dangling one read as no
 * seed and the write created the file it named. A link is also something someone made, which a
 * seed never replaces. A seed that is written goes through [RecordWrites], so it is replaced
 * whole beside its own name rather than written in place.
 *
 * A seed is read once, through [RecordReads], the bound its writer takes (#387): only a regular
 * file is read — a FIFO there blocked the boot — and anything else standing there is refused,
 * which leaves it alone and is said, as a seed that could not be written always was. [SeedLedger]
 * hashes those bytes; it read the file again itself, following a link.
 */
class VaultDashboard(private val recordRoot: Path, private val ledger: SeedLedger) {
    private val writes = RecordWrites.underRoot(recordRoot, SEEDS.toSet())
    private val reads = RecordReads.underRoot(recordRoot, SEEDS.toSet())

    fun ensure() {
        SEEDS.forEach { seed -> runCatching { seed(seed) }.onFailure { warn(it) } }
    }

    // Read once, through the bound its writer takes: a seed that is no regular file is refused, which leaves it alone.
    private fun seed(seed: String) {
        val file = recordRoot.resolve(seed)
        if (Files.isSymbolicLink(file)) return leftAlone(file)
        val shipped = shipped(seed)
        val current = reads.readAllBytes(file)
        if (current != null && !replaceable(seed, current, shipped)) return
        if (!writes.replaceOrSkip(file, shipped)) return
        ledger.record(seed, shipped)
        announced(file, fresh = current == null)
    }

    // Ours to replace only when still exactly as this server last wrote it, and not when it already is what we ship.
    private fun replaceable(seed: String, current: ByteArray, shipped: String): Boolean {
        if (adopted(seed, current, shipped)) return false
        return ledger.isUnchanged(seed, current)
    }

    private fun leftAlone(file: Path) {
        logger.warn(LEFT_ALONE, file)
    }

    private fun announced(file: Path, fresh: Boolean) {
        if (fresh) return logger.info("Wrote {} — seeded once; it is yours to edit from here", file)
        logger.info("Refreshed {} — it was still exactly as this server wrote it, so it was ours to update", file)
    }

    /**
     * A file that already **is** what we would write is ours, whatever the ledger remembers — so
     * record it and stop (#314).
     *
     * This is the only claim of ownership that cannot cost anybody an edit, because there is no
     * edit: the bytes are identical, and writing them would be a no-op. Without it the ledger has
     * a one-way door. `dashboard.base` reaches this state on its own — Obsidian rewrites it the
     * first time the Base view renders, stripping the comments, and since 2026-08-14 that
     * stripped form is exactly what ships. A vault seeded before then holds our current bytes
     * under a ledger entry naming the old ones, and every later improvement would be declined as
     * an edit the reader never made.
     *
     * Recorded only when the ledger disagrees, so a settled vault does not rewrite `.ps/seeds.json`
     * on every boot.
     */
    private fun adopted(seed: String, current: ByteArray, shipped: String): Boolean {
        if (!current.contentEquals(shipped.toByteArray())) return false
        if (!ledger.isUnchanged(seed, current)) ledger.record(seed, shipped)
        return true
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

        const val LEFT_ALONE =
            "Left {} alone: it is a symbolic link, and a seed is written neither through one nor over one"
    }
}
