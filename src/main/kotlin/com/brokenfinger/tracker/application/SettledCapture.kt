package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.ProblemKind
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
    /**
     * Algorithm or SQL, taken from the channel we subscribed on rather than read back out of a
     * frame: the server chose that channel by exactly this, so it is known before the first
     * frame and is not a value we were handed (#256).
     */
    val kind: ProblemKind,
    /** Time on this problem, measured by whoever owns the timer, never by the writer. */
    val elapsedSec: Long,
    /**
     * What the sensor saw for this problem, when there was a sensor (#120). Null for a watch
     * started by hand, so nothing downstream may read its absence as zero.
     */
    val observation: SensorObservation? = null,
    /**
     * Every frame the assembler accepted, in arrival order and exactly as received — the
     * basis the capture key is derived from (#149). Empty when the grading was abandoned
     * before any frame landed, and the key then falls back to [rawSessionId].
     *
     * Only the accepted frames, so the live path and a replay of the same raw log agree:
     * both skip a line that yields no facts, and a key that disagreed between them would
     * make every reconciliation a duplicate record.
     */
    val frames: List<String> = emptyList(),
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

    fun toRecord(ts: OffsetDateTime, attempt: Int, key: CaptureKey, rawPath: String?, sincePrevSec: Long? = null) =
        SubmissionRecord(
            ts = ts,
            lessonId = lessonId,
            title = problem?.title.orEmpty(),
            level = problem?.level,
            part = problem?.partTitle,
            acceptanceRate = problem?.acceptanceRate,
            tags = problem?.tags.orEmpty(),
            language = language,
            kind = kind,
            action = action(),
            attempt = attempt,
            elapsedSec = elapsedSec,
            sincePrevSec = sincePrevSec,
            sensor = observation,
            captureKey = key,
            outcome = session.outcome,
            verdict = session.verdict,
            score = session.score,
            rating = session.rating,
            testcases = session.testcases,
            tcSummary = TestcaseSummary.of(session.testcases, session.testcasesComplete),
            rawPath = rawPath,
            // The verdict is durable the moment this line is written; the code is fetched
            // afterwards and its failure is never this record's failure (design §5.2).
            codePending = true,
            errorText = session.errorText,
        )

    // A grading abandoned before any frame was accepted has nothing to digest, and the
    // session id is unique per capture — the right fallback for something that will never
    // be replayed, because there is nothing to replay.
    private fun keyBasis(): List<String> = frames.filter { it.isNotBlank() }.ifEmpty { listOf(rawSessionId.value) }
}
