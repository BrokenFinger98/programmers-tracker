package com.brokenfinger.tracker.application

import java.nio.file.Path
import java.time.Instant

/**
 * Stage 1 of the capture pipeline — the durable queue
 * ([[decisions/2026-08-05-capture-pipeline-stages]]).
 *
 * Every received frame is appended here **before** anything else happens, because a
 * grading result Programmers has already broadcast can never be fetched again
 * (protocol doc §11). Once [append] returns, no later failure — parse, record write,
 * code fetch, git — can destroy the verdict: it is replayable from this file.
 *
 * There is deliberately no in-memory queue beside it. Whatever is still listed by
 * [unprocessed] at startup is a session a crash left behind.
 */
interface RawSessionLog {
    /**
     * Opens a live session for the given lesson and returns its identity. The lesson
     * number is a naming input only — a directory name, not an identity — so it stays a
     * plain [Long] rather than a `LessonId` this port would have nothing to do with.
     */
    fun start(lessonId: Long): RawSessionId

    /**
     * Appends one frame as a single line, exactly as received. Never re-serializes
     * (dev rules §2.4) and never fails on ordinary conditions such as a missing
     * directory or a first write.
     */
    fun append(session: RawSessionId, frameText: String)

    /**
     * Copies a terminated session's frames to [destination], leaving the source in place,
     * and returns that path.
     *
     * Copy rather than move because the source is the crash-recovery queue ([unprocessed]):
     * it must survive until the record line naming this destination is durable, or an
     * interrupted write loses the grading with nowhere left to look (#95). [discard]
     * retires it afterwards.
     */
    fun complete(session: RawSessionId, destination: Path): Path

    /** Retires a session whose record is durable; the frames now live at their destination. */
    fun discard(session: RawSessionId)

    /**
     * Retires a session that was never copied — a run owns no attempt file (design §5.1) —
     * by taking it off the work list **without destroying it**. The frames are the only
     * original that grading will ever have, and the constitution forbids discarding
     * originals; leaving them on the work list instead made every boot re-read every run
     * ever captured (#99).
     */
    fun setAside(session: RawSessionId)

    /**
     * Keeps a frame that belongs to no grading, so a missed `start` costs the verdict but
     * not the evidence (#107).
     *
     * Never on the [unprocessed] work list: without a `start` there is no action and no
     * identity, so no record can be derived from these however often they are replayed.
     * They are kept to be *read* — by a person asking why a grading went missing, or
     * noticing that Programmers changed its framing (dev rules §2.3).
     */
    fun orphaned(lessonId: Long, frameText: String)

    /** Sessions still awaiting stage 2 — the crash-recovery work list, oldest first. */
    fun unprocessed(): List<RawSession>
}

/**
 * Identity of one raw session log. It doubles as a file name, so it is constrained to
 * characters every supported filesystem accepts and can never step out of its directory.
 */
@JvmInline
value class RawSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "raw session id must not be blank" }
        require(!value.contains("..")) { "raw session id must not traverse directories: $value" }
        require(SAFE.matches(value)) { "raw session id must be filesystem-safe: $value" }
    }

    private companion object {
        // Excludes every character Windows reserves in a file name, path separators included.
        val SAFE = Regex("[A-Za-z0-9._-]+")
    }
}

/** One unprocessed session found on disk. */
data class RawSession(val id: RawSessionId, val lessonId: Long, val startedAt: Instant, val path: Path)
