package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.SubmissionRecord
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The records repository's front page, `README.md`: the index, where github.com opens the repository (#414).
 *
 * ### What it says
 *
 * What the repository is and where the guide is, in both languages, then the counts and the table
 * `problems/README.md` carries ([IndexTable]), its links starting at the root. Counts and recorded facts, never a
 * verdict ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]). A fresh repository gets it before its first
 * record, with a count of none and no table: the page that says what the repository is helps its first visitor, and
 * it is the same bytes at every boot until something is recorded. A page already there is never zeroed by a history
 * read as empty, which is left as `problems/README.md` leaves it (#414's review).
 *
 * ### Whose it is
 *
 * The server's while it is **exactly the page the server wrote**, the owner's the moment one character differs — the
 * rule [SeedLedger] keeps for the seeds (#300), and never a mix of the two in one file. What the server wrote is not
 * looked up in the ledger, though, and cannot be:
 * - the page changes at every grading, and the ledger is written through the state directory, which asks git what
 *   it tracks before every write (#360) — a git process per grading;
 * - `.ps` is never committed, so a clone, or a ledger lost or corrupt, has no record, and no record means edited: the
 *   front page would stop moving on every other machine while claiming to be the index;
 * - a crash between the page and the ledger leaves them disagreeing, and the page is taken for an edit the same way.
 *
 * So the page carries its own proof: [PageSeal], a first line holding the digest of the rest, written and moved over
 * the old page as one file. Anything else at `README.md` — an edit of the page, a page of the owner's own, the guide
 * an older server seeded and someone edited, a link — is left alone, and said once: the index carries on in
 * `problems/README.md`. Nothing there at all is the server's, which is how a fresh repository gets the page, and how
 * an owner who deletes theirs gets the index back. The guide an older server seeded and nobody touched is the
 * ledger's to judge, at boot: [VaultDashboard] takes it away, and this then finds nothing there.
 *
 * Written through [RecordWrites] like every derived page: beside its name and moved over it, never through a link, and
 * skipped and said when refused or failed (#361, #407). Read through [RecordReads], the same walk, so a page the read
 * refuses — a link, a FIFO, a directory — is never the server's to replace.
 */
class RootIndex(private val layout: RecordLayout) {
    private val writes = RecordWrites.underRoot(layout, setOf(RecordLayout.ROOT_INDEX))
    private val reads = RecordReads.underRoot(layout.configuredRoot(), setOf(RecordLayout.ROOT_INDEX))
    private val table = IndexTable(layout::problemNoteLinkFromRoot)
    private val said = SaidOnce()

    /**
     * Writes the front page for the whole history and returns it — or null when it was not written: the page there is
     * the owner's, which is said once; an empty history met a page already there, which is left as it is; or
     * [RecordWrites] did not write it and has said why.
     */
    fun write(records: List<SubmissionRecord>): Path? {
        val file = layout.rootIndex()
        return when (standingAt(file)) {
            Standing.NOTHING -> written(file, records)
            Standing.SEALED -> rewritten(file, records)
            Standing.THE_OWNERS -> leftAlone(file)
        }
    }

    // What stands at the front page: nothing, a page this server sealed, or anything else. What the read refuses is
    // no page of the server's.
    private fun standingAt(file: Path): Standing {
        val current = try {
            reads.readAllBytes(file)
        } catch (refused: IOException) {
            return Standing.THE_OWNERS
        } ?: return Standing.NOTHING
        if (PageSeal.isIntact(current)) return Standing.SEALED
        return Standing.THE_OWNERS
    }

    // A history read as empty — a log moved away, every line unreadable — is no count to put on a page already there:
    // it is left as it is, as problems/README.md is (#414's review).
    private fun rewritten(file: Path, records: List<SubmissionRecord>): Path? {
        if (records.isEmpty()) return null
        return written(file, records)
    }

    private fun written(file: Path, records: List<SubmissionRecord>): Path? =
        file.takeIf { writes.replaceOrSkip(it, PageSeal.sealed(HEADER + table.render(records))) }

    private fun leftAlone(file: Path): Path? {
        said.say(file) { logger.warn(LEFT_ALONE, file) }
        return null
    }

    private enum class Standing { NOTHING, SEALED, THE_OWNERS }

    private companion object {
        val logger = LoggerFactory.getLogger(RootIndex::class.java)

        /** English, as all the server writes; the Korean guide is named in Korean, as every language switch is. */
        const val HEADER = "# Programmers solving records\n\n" +
            "Written by [programmers-tracker](https://github.com/BrokenFinger98/programmers-tracker) after every " +
            "grading. The guide: [English](GUIDE.md) · [한국어](GUIDE.ko.md).\n"

        const val LEFT_ALONE =
            "Left {} alone: it is not the front page this server last wrote — an edit, a page of your own or a " +
                "link — so it is yours, and the index is in problems/README.md alone. Remove it and the index " +
                "takes its place again (#414). Said once until the next start."
    }
}

/**
 * How a page says it is exactly what this server wrote (#414): its first line is a comment holding the SHA-256 of
 * everything after it. A comment, because no renderer shows one — github.com and Obsidian's reading view start the
 * page at its title.
 *
 * A page is intact when its first line is a seal and the seal's digest is the SHA-256 of the body after the first line
 * break. So a byte of the body changed breaks it, and so does a line added above the seal, inside it — which splits it
 * — or below it. A seal copied onto another page vouches for nothing. Line endings alone are set aside, because git's
 * `core.autocrlf` checks a page out with CRLF and that is the checkout's doing rather than an edit; the server only
 * ever writes LF.
 *
 * **The framing is a format; the words are free** (#414's review). Every page already deployed carries a seal framed
 * as [FRAMING] reads it — one line, from `<!--` to ` -->`, holding `sha256:` and exactly 64 lowercase hex — so a
 * change to the framing needs a reader for the old one beside it, or every such page is taken for an edit and never
 * written again. The [NOTE] between is what a person reads, and is not checked: a release may reword it, and a page
 * sealed under older words is still the server's and is rewritten under the new.
 */
internal object PageSeal {
    private const val NOTE = "Written by programmers-tracker. Edit this page and it is yours: it is never written " +
        "again, and the index stays in problems/README.md."

    private val FRAMING = Regex("""<!--.*sha256:([0-9a-f]{64})(?![0-9a-f]).* -->""")

    /** [body] under its seal. */
    fun sealed(body: String): String = "<!-- $NOTE sha256:${digestOf(body)} -->\n$body"

    /** Whether [bytes] are a body under a seal holding its digest, line endings aside and the seal's words unread. */
    fun isIntact(bytes: ByteArray): Boolean {
        val text = String(bytes, UTF_8).replace("\r\n", "\n")
        val end = text.indexOf('\n')
        if (end < 0) return false
        val digest = FRAMING.matchEntire(text.substring(0, end))?.groupValues?.get(1) ?: return false
        return digest == digestOf(text.substring(end + 1))
    }

    private fun digestOf(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
}
