package com.brokenfinger.tracker.adapter.store

import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.security.MessageDigest

/**
 * What the server last wrote for each seeded file, so it can tell **untouched** from **edited**
 * (#300).
 *
 * `dashboard.base` and the vault READMEs are written once and then belong to the reader (#254) —
 * *editing is respected forever; deletion is read as loss, not intent.* That rule is right and is
 * not changing. What it did not consider is the third state: a file **nobody has touched**, which
 * is not the reader's yet and is only out of date. Twice on 2026-08-13 an improvement reached the
 * shipped seed and the one existing vault only because someone edited it by hand.
 *
 * The test is exact bytes. If a file still hashes to what we recorded writing, it is ours to
 * replace; one character different and it is theirs, permanently.
 *
 * **No record means edited.** A vault seeded before this ledger existed gets left alone — absence
 * is not permission, and the cost of being wrong runs one way only: overwriting something a person
 * wrote cannot be undone from here, while leaving a stale file costs a hand-copy that was already
 * the status quo.
 *
 * Lives in `.ps/` beside the timers and the backup marker (#126) — process state the record
 * repository gitignores, because it says nothing about anybody's solving history. Written by
 * temp-and-rename through [AtomicStateFile.under], and only while [state] allows it: a pull can
 * deliver `.ps/seeds.json` as a link, and the ledger overwrote what it led to at every boot (#360).
 */
class SeedLedger(recordRoot: Path, state: StateDirectory) {
    private val ledger = AtomicStateFile.under(recordRoot, LEDGER, state)

    /**
     * True only when [current] is exactly what we last wrote for [seed] — never for a seed we have no record of.
     *
     * It hashes the bytes it is handed and reads no seed itself (#387). It used to read the file at the path it was
     * given, through a link if one stood there; the one read of a seed is now [VaultDashboard]'s, through the bound its
     * writer takes.
     */
    fun isUnchanged(seed: String, current: ByteArray): Boolean {
        val recorded = recorded()[seed] ?: return false
        return recorded == hashOf(current)
    }

    fun record(seed: String, content: String) {
        ledger.write(JSON.encodeToString(recorded() + (seed to hashOf(content.toByteArray()))))
    }

    /**
     * Unreadable or malformed reads as empty, which resolves to "edited" for every seed and so
     * changes nothing. A corrupt ledger must never be a reason to overwrite somebody's file.
     */
    private fun recorded(): Map<String, String> =
        runCatching { JSON.decodeFromString<Map<String, String>>(ledger.read() ?: NOTHING_RECORDED) }
            .getOrDefault(emptyMap())

    private fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LEDGER = "seeds.json"
        const val NOTHING_RECORDED = "{}"

        val JSON = Json { prettyPrint = true }
    }
}
