package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.domain.calc.TagCoverage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Stage 3 of the capture pipeline — the late, retryable code attachment
 * ([[decisions/2026-08-05-capture-pipeline-stages]]).
 *
 * It runs only after the record is already durable, and that ordering is the whole point: a
 * grading Programmers has broadcast can never be recaptured (protocol doc §11), while the code
 * it graded stays on the problem page and can be fetched again (protocol doc §10). So **no
 * fetch failure may ever fail or unwind a record.** Every [CodeFetch] branch other than
 * [CodeFetch.Fetched] leaves the stored record exactly as stage 2 wrote it, `codePending`
 * still true, and writes no file at all.
 *
 * ### How `codePending` is cleared
 *
 * By **appending a corrected copy of the record**, not by rewriting its line and not by a
 * sidecar index. Readers resolve a capture key to its newest line through [RecordHistory].
 *
 * - *Rewriting the line in place* means rewriting the whole file, since the corrected JSON is
 *   longer than what it replaces. A crash halfway through would put every verdict in the log
 *   at risk in order to clear one boolean on one record — spending the unrecoverable artifact
 *   on the recoverable one, which is the inversion this pipeline exists to undo.
 * - *A sidecar index* would make record state readable from two places. Decisions 2 and 5 of
 *   [[decisions/2026-08-05-write-serialization]] delete second sources rather than guard them.
 * - *Appending* keeps the file append-only, which is what the attempt authority rests on. The
 *   correction repeats the record's `lessonId`, `action`, `attempt` and `captureKey`, so
 *   neither in-memory index [RecordWriter] restores at startup can move: the attempt counter
 *   takes the **highest** number per problem and a repeat cannot raise it, and the dedup index
 *   is a **set** of capture keys that a repeat collapses into. Both are pinned by tests.
 *
 * The accepted cost is one extra line per attachment, and readers that must resolve the log
 * rather than read it line by line.
 *
 * ### What runs where
 *
 * The fetch runs on the caller's context; only the derived writes enter [writerDispatcher],
 * and they enter it as one section — artifacts, the correction line and the README together —
 * so nothing interleaves with another grading's allocation and append
 * ([[decisions/2026-08-05-write-serialization]] decision 1). Holding an HTTP round trip inside
 * the single writer thread would stall every other grading behind the network, which is
 * precisely what a late, retryable attachment exists not to do.
 */
class CodeAttachment(
    private val fetcher: CodeFetcher,
    private val store: RecordStore,
    private val artifacts: DerivedArtifacts,
    private val catalog: ProblemCatalog,
    private val writerDispatcher: CoroutineDispatcher,
) : RecordAttachment {
    /** Attaches one record's code. Never throws for a failed fetch — the record is not ours to lose. */
    override suspend fun attach(record: SubmissionRecord): AttachOutcome = applied(record, fetched(record))

    /**
     * Retries every record the log still resolves to `codePending`, oldest first.
     *
     * Safe on every boot and safe to repeat: a record that succeeded is no longer pending, one
     * that failed is simply pending again next time, and re-attaching writes the same bytes
     * through the artifacts' temp-then-replace. The pass stops at the first blocking outcome,
     * because an expired session and a rate limit are shared by every remaining record.
     */
    suspend fun attachPending(): AttachReport {
        val pending = RecordHistory.of(store.read()).filter { it.codePending }
        if (pending.isEmpty()) return AttachReport()
        logger.info("Retrying the code attachment of {} record(s) left pending", pending.size)
        return passOver(pending)
    }

    private suspend fun passOver(pending: List<SubmissionRecord>): AttachReport {
        var report = AttachReport()
        for (record in pending) {
            report = report.and(attach(record))
            if (report.hasStopped()) return report
        }
        return report
    }

    private suspend fun fetched(record: SubmissionRecord): CodeFetch =
        runCatching { fetcher.fetch(record.lessonId, record.language) }.getOrElse { failed(it) }

    private suspend fun applied(record: SubmissionRecord, outcome: CodeFetch): AttachOutcome {
        if (outcome !is CodeFetch.Fetched) return deferred(record, outcome)
        withContext(writerDispatcher) { attached(record, outcome) }
        return AttachOutcome.ATTACHED
    }

    // The confined section: every file this record's code produces lands together with the line
    // that names them, so no other grading's write can slip between the two.
    private fun attached(record: SubmissionRecord, fetched: CodeFetch.Fetched) {
        val code = fetched.code
        val written = artifacts.writeCode(record, code)
        artifacts.writeRunner(record, code)
        // Before the README, which links it. A statement that arrived is written once; a page
        // that carried none leaves no file, because absent is honest and a placeholder is not.
        fetched.statement?.let { artifacts.writeStatement(record, it) }
        val corrected = record.copy(
            codePath = written.codePath,
            codePending = false,
            diffFromPrev = written.diffFromPrev,
        )
        store.append(SubmissionRecordJson.encode(corrected))
        artifacts.writeReadme(lessonHistory(corrected.lessonId))
        artifacts.writeIndex(RecordHistory.of(store.read()))
        artifacts.writeTagNotes(coverageOf(corrected.tags))
    }

    /**
     * The tag map for the tags this record carries, and no others — the rest are unchanged and
     * rewriting them would be noise in the record repository's history.
     *
     * **Recomputed from the log rather than incremented.** A held counter is a second authority
     * that a reconciliation or a restart can put out of step, which is the defect family this
     * repository spent 2026-08-12 removing.
     */
    private fun coverageOf(tags: List<String>): List<TagCount> {
        if (tags.isEmpty()) return emptyList()
        return tagCoverage().filter { it.tag in tags }
    }

    /**
     * The whole map, for the caller that has no single problem in hand — startup.
     *
     * Every note, not only the missing ones. Startup is also where reconciliation recovers
     * records a crash left behind, and those change counts; refreshing only what is absent would
     * leave a note disagreeing with the log until some later grading happened to touch that tag.
     * Identical bytes are not a change, so git sees nothing for the untouched ones.
     */
    fun refreshVault() {
        refreshProblemPages()
        artifacts.writeIndex(RecordHistory.of(store.read()))
        artifacts.writeTagNotes(tagCoverage())
    }

    /**
     * Every problem page, because the tag notes alone are half a map.
     *
     * Obsidian draws edges from links, and a page written by an earlier build carries none — so
     * a vault upgraded into the tag map would show every tag isolated and no edge at all, which
     * is the opposite of what the map is for. Rewriting is free when nothing changed: the page
     * is a function of its records, so an unchanged problem produces identical bytes.
     */
    private fun refreshProblemPages() =
        RecordHistory.of(store.read()).groupBy { it.lessonId }.values.forEach { artifacts.writeReadme(it) }

    private fun tagCoverage(): List<TagCount> {
        val submits = RecordHistory.of(store.read()).filter { it.action == GradingAction.SUBMIT }
        return TagCoverage.of(
            catalogued = catalog.all().map { it.toSummary() },
            passed = submits.filter { it.verdict == Verdict.PASS }.map { it.lessonId }.toSet(),
            submitted = submits.map { it.lessonId }.toSet(),
        )
    }

    // Read back after the correction landed, so the page states what the log now resolves to.
    private fun lessonHistory(lessonId: Long): List<SubmissionRecord> =
        RecordHistory.of(store.read()).filter { it.lessonId == lessonId }

    private fun deferred(record: SubmissionRecord, outcome: CodeFetch): AttachOutcome {
        if (outcome is CodeFetch.Unauthenticated) return unauthenticated(record)
        if (outcome is CodeFetch.RateLimited) return backedOff(record)
        logger.warn("Lesson {} keeps its code pending — {}", record.lessonId, reasonOf(outcome))
        return AttachOutcome.DEFERRED
    }

    /**
     * There is no auth-state holder in the application yet, so an expired session is reported
     * here as loudly as it can be: at ERROR, under the `AUTH` marker the subscription path will
     * share once one exists. It must never read like a page that happened to be missing — the
     * difference is "paste a fresh cookie" against "there is nothing there to fetch".
     */
    private fun unauthenticated(record: SubmissionRecord): AttachOutcome {
        logger.error("AUTH: the session is no longer valid — lesson {} keeps its code pending", record.lessonId)
        return AttachOutcome.BLOCKED
    }

    private fun backedOff(record: SubmissionRecord): AttachOutcome {
        logger.warn("Rate limited — lesson {} keeps its code pending and the pass backs off", record.lessonId)
        return AttachOutcome.BLOCKED
    }

    // A fetcher that throws is a fetcher that failed, and it must travel no further than this:
    // the record it would otherwise unwind is the one artifact that cannot be recaptured.
    private fun failed(cause: Throwable): CodeFetch =
        CodeFetch.Unavailable("the fetch threw ${cause.javaClass.simpleName}")

    private fun reasonOf(outcome: CodeFetch): String =
        (outcome as? CodeFetch.Unavailable)?.reason ?: "no code was returned"

    private companion object {
        val logger = LoggerFactory.getLogger(CodeAttachment::class.java)
    }
}

/**
 * Stage 3 as the capture path sees it — a record has landed and its code can now be fetched.
 *
 * The seam exists because [ChannelCapture] must not know what attaching involves: a page
 * fetch, three files and a README, each failing for reasons of its own that are stage 3's to
 * absorb. It also keeps the capture path testable without a fetcher.
 */
fun interface RecordAttachment {
    suspend fun attach(record: SubmissionRecord): AttachOutcome
}

/** What one attachment did. Only [ATTACHED] clears `codePending`. */
enum class AttachOutcome {
    ATTACHED,

    /** Still pending for a reason of this record's own; the next pass tries it again. */
    DEFERRED,

    /** Still pending for a reason every other record shares — an expired session, a rate limit. */
    BLOCKED,
}

/** What one pass over the pending records did. Every record attempted lands in exactly one count. */
data class AttachReport(val attached: Int = 0, val deferred: Int = 0, val blocked: Int = 0) {
    fun and(outcome: AttachOutcome): AttachReport = when (outcome) {
        AttachOutcome.ATTACHED -> copy(attached = attached + 1)
        AttachOutcome.DEFERRED -> copy(deferred = deferred + 1)
        AttachOutcome.BLOCKED -> copy(blocked = blocked + 1)
    }

    /** Asking again would only be told the same thing, so the pass ends here. */
    fun hasStopped(): Boolean = blocked > 0
}

/**
 * The files a record's code produces, as `application` sees them.
 *
 * Naming, diffing and the README all live in the store adapter, and `application` does not
 * import `adapter` (dev rules §1); the composition root satisfies this with
 * `FileDerivedArtifacts`.
 */
interface DerivedArtifacts {
    /** Writes `Solution.<ext>`, plus the attempt copy when the record owns one, and reports both. */
    fun writeCode(record: SubmissionRecord, code: String): AttachedCode

    /**
     * Regenerates the problem's runner from the freshly attached code and the captured
     * examples (#37) — or removes a stale one, because a runner built from yesterday's code
     * would test something the user is no longer looking at. Refusals are logged with their
     * reason, never written as a best-effort file.
     */
    fun writeRunner(record: SubmissionRecord, code: String)

    /**
     * Writes the problem statement **once**, and never again (#275).
     *
     * Write-if-absent because the README beside it is regenerated on every grading, and a
     * statement living in a regenerated file would have to be re-fetched every time to survive.
     * The same split `notes.md` has: this repository holds files the server owns, files the
     * reader owns, and — now — one it writes once and then leaves alone.
     */
    fun writeStatement(record: SubmissionRecord, markdown: String)

    /** Rewrites one problem's README from its records, oldest first. */
    fun writeReadme(records: List<SubmissionRecord>)

    /**
     * Rewrites `problems/README.md` — what has been solved, in the one form a browser renders
     * (#292). Takes the **whole** history rather than one problem's, because an index of one
     * problem is a problem page.
     */
    fun writeIndex(records: List<SubmissionRecord>)

    /**
     * Rewrites the vault's tag map for the counts given (#229) — one problem's tags after a
     * record, every catalogued tag at startup.
     *
     * The counts are handed in rather than computed here, because deciding them is a
     * calculator's job and this port only writes files.
     */
    fun writeTagNotes(counts: List<TagCount>)
}

/**
 * Where one record's code came to rest, and what changed since the previous attempt.
 *
 * The path is relative to the record repository and forward-slashed: that repository is meant
 * to be cloned onto another machine, and an absolute path would not survive the trip.
 */
data class AttachedCode(val codePath: String, val diffFromPrev: String? = null)
