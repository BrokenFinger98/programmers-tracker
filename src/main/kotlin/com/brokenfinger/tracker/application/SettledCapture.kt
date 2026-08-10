package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SensorObservation
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.TestcaseSummary
import java.time.OffsetDateTime

/**
 * A grading that has already settled, together with everything the writer cannot work out
 * for itself — stage 2's input ([[decisions/2026-08-05-capture-pipeline-stages]]).
 *
 * The values that are *not* here are as deliberate as the ones that are. Catalog metadata,
 * the problem timer and the code attachment all arrive on their own schedules; inventing
 * them at write time would file guesses next to a measured verdict, which is the silent
 * wrong data the constitution ranks as the worst outcome. They stay at their record
 * defaults until the stage that owns them fills them in.
 */
data class SettledCapture(
    val session: GradingSession,
    /** The raw log this grading was assembled from — still the durable copy of its frames. */
    val rawSessionId: RawSessionId,
    val lessonId: Long,
    /**
     * What the shipped catalog knows about this problem, or null when it does not know it —
     * a problem published after the catalog was built, which is ordinary rather than an error
     * ([[decisions/2026-08-06-shipped-problem-catalog]]). Null stays null all the way to the
     * record: an unknown title is absent, never a placeholder that reads like a measurement.
     */
    val problem: CatalogEntry?,
    val language: String,
    /** Time on this problem, measured by whoever owns the timer, never by the writer. */
    val elapsedSec: Long,
    /**
     * What the sensor saw for this problem, when there was a sensor (#120). Null for a watch
     * started by hand, so nothing downstream may read its absence as zero.
     */
    val observation: SensorObservation? = null,
    /**
     * The frame that ended the stream, exactly as received. Null when none arrived — a
     * timeout or a disconnect — and the key then falls back to [rawSessionId], which a
     * replay of the same raw log repeats exactly.
     */
    val terminalFrame: String? = null,
) {
    /**
     * The action this grading was requested with. Throws when the stream announced none:
     * substituting a default would file a run under a submit's number and corrupt the
     * sequence silently. The frames are already durable, so this costs a record, not data.
     */
    fun action(): GradingAction = requireNotNull(session.action) { "grading of lesson $lessonId announced no action" }

    /** Our own identity for this grading — the dedup index's key (dev rules §5, design §5.2). */
    fun captureKey(): CaptureKey = CaptureKey.of(lessonId, action(), keyBasis())

    /**
     * Whether this grading's frames belong in an attempt file. Only a submit that owns a
     * number does; a run keeps the previous submit's number and creates no `attempts/NNN.*`
     * files at all (design §5.1).
     */
    fun movesRaw(attempt: Int): Boolean = action() == GradingAction.SUBMIT && attempt > AttemptAuthority.NONE

    fun toRecord(ts: OffsetDateTime, attempt: Int, key: CaptureKey, rawPath: String?) = SubmissionRecord(
        ts = ts,
        lessonId = lessonId,
        title = problem?.title.orEmpty(),
        level = problem?.level,
        part = problem?.partTitle,
        acceptanceRate = problem?.acceptanceRate,
        tags = problem?.tags.orEmpty(),
        language = language,
        action = action(),
        attempt = attempt,
        elapsedSec = elapsedSec,
        sensor = observation,
        captureKey = key,
        outcome = session.outcome,
        verdict = session.verdict,
        testcases = session.testcases,
        tcSummary = TestcaseSummary.of(session.testcases, session.testcasesComplete),
        rawPath = rawPath,
        // The verdict is durable the moment this line is written; the code is fetched
        // afterwards and its failure is never this record's failure (design §5.2).
        codePending = true,
        errorText = session.errorText,
    )

    private fun keyBasis(): String = terminalFrame?.takeIf { it.isNotBlank() } ?: rawSessionId.value
}
