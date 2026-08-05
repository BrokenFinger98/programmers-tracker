package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.AttachedCode
import com.brokenfinger.tracker.application.DerivedArtifacts
import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.domain.SubmissionRecord
import java.nio.file.Path

/**
 * The store's [DerivedArtifacts] — [CodeArtifacts] for the code files and [ProblemReadme] for
 * the page, joined so `application` sees one port instead of the layout's internals.
 *
 * It adds no rules of its own. Which files a run and a submit each own is [CodeArtifacts]'
 * decision (design §5.1), and what the page says is [ProblemReadme]'s; all this class settles
 * is **which file the record points at**, and that it points at it relatively.
 *
 * Thread confinement is the caller's: every method here is called from inside stage 3's
 * confined section ([[decisions/2026-08-05-write-serialization]] decision 1).
 */
class FileDerivedArtifacts(private val recordRoot: Path, records: RecordStore) : DerivedArtifacts {
    private val artifacts = CodeArtifacts(recordRoot, records)
    private val readme = ProblemReadme(RecordLayout(recordRoot))

    /**
     * The attempt copy is what a submit points at: it is the code of *that* grading and never
     * changes again, while `Solution.<ext>` is overwritten by the next run. A run owns no
     * attempt file, so it points at the solution file it just refreshed.
     *
     * The diff is taken before the attempt copy is written for readability only — it compares
     * against the *previous* attempt, so the order does not matter to the result.
     */
    override fun writeCode(record: SubmissionRecord, code: String): AttachedCode {
        val diff = artifacts.diffFromPrev(record, code)
        val latest = artifacts.writeLatest(record, code)
        val attempt = artifacts.writeAttempt(record, code)
        return AttachedCode(relativeOf(attempt ?: latest), diff)
    }

    override fun writeReadme(records: List<SubmissionRecord>) {
        readme.write(records)
    }

    // Forward slashes whatever the host uses — the same shape `rawPath` already has, so a
    // record repository cloned onto another machine still resolves every path it carries.
    private fun relativeOf(path: Path): String =
        recordRoot.toAbsolutePath().relativize(path.toAbsolutePath()).joinToString("/")
}
