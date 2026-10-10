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
 * [unprocessed] at startup is a session a crash left behind. The one exception is a store that
 * refuses where its files would go (#360): it then holds frames in memory rather than write them
 * where a commit could carry them, and only those are lost to a crash.
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
     * Takes back the [copy] that [complete] made for [session] when the record naming it was never appended, and
     * answers whether nothing is left there (#387's review). The source stays on the work list, so a replay makes the
     * copy again under the number it is then given; a copy left behind took that number from every later grading.
     *
     * Only this log's own copy, and only while every frame in it is also on the work list: one that holds frames kept
     * only in memory is their one copy on disk, and is kept — false, as for anything at [copy] that is not a regular
     * file.
     */
    fun withdraw(session: RawSessionId, copy: Path): Boolean

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

    /**
     * Sessions still awaiting stage 2 — the crash-recovery work list, oldest first.
     *
     * Empty, too, while the store refuses the place they lie, as it refuses a write there (#377): a pull
     * can deliver a file that reads as a session, and replaying it would record a grading the owner never
     * made. They are left where they are, unread, and the store says how many.
     */
    fun unprocessed(): List<RawSession>

    /**
     * What the last [unprocessed] left on the work list without returning it (#377): sessions refused with
     * where they lie, named in git's history, kept while git could not say, or not regular files. They stay
     * there, unread, until a later start; until then no record represents them, and a reader of the history
     * has to be told so (#169). Nothing left, before [unprocessed] has run.
     */
    fun unreplayed(): LeftUnreplayed

    /**
     * What has been orphaned, per lesson, so it can be reported rather than merely written.
     *
     * [orphaned] announces each frame once, in a warning, at the moment it arrives. After
     * that nothing mentions them again — not at startup, not over MCP — so the record has
     * holes while every consumer believes it is complete (#169). The consumer that matters
     * is an AI asked to diagnose weaknesses from this history, and a confident diagnosis over
     * a record with silent holes is worse than no diagnosis.
     *
     * What the store would not read is part of the answer (#378): a refusal that answered
     * "none" would tell every reader the history is whole.
     */
    fun orphans(): Orphans
}

/**
 * The orphaned frames the store could read ([read]), and what it would not read: [unread] files named
 * like orphans that it did not open, and whether it could not list them at all ([unlisted]) (#378).
 */
data class Orphans(val read: List<OrphanedFrames>, val unread: Int, val unlisted: Boolean) {
    /** Whether there is nothing to tell: no orphan read, none passed over, nothing unlisted. */
    fun isNothing(): Boolean = read.isEmpty() && unread == 0 && !unlisted

    companion object {
        /** No orphan anywhere the store could look. */
        val NONE = Orphans(emptyList(), 0, unlisted = false)
    }
}

/**
 * Frames kept for one lesson that belong to no grading.
 *
 * Deliberately a count and a path, not a parse. These frames cannot become records — the
 * missing `start` carries the testcase ids and the problem's examples, and binding a segment
 * of this file to the attempt it belongs to would be inference, which is the one thing the
 * constitution forbids doing with an identifier. So this type says *that* something is
 * stranded and *where to read it*, and stops.
 */
data class OrphanedFrames(val lessonId: Long, val frames: Int, val path: Path)

/**
 * Sessions a start left on the work list without replaying them (#377): [counted] of them, and whether a raw
 * directory that could not be listed — through a link, or at all — may hold more ([uncounted]). A count and a
 * flag, never a guess at how many lie where nothing was listed.
 */
data class LeftUnreplayed(val counted: Int, val uncounted: Boolean) {
    /** Whether the work list was replayed whole. */
    fun isNothing(): Boolean = counted == 0 && !uncounted

    companion object {
        /** A start that left nothing, or none yet. */
        val NOTHING = LeftUnreplayed(0, uncounted = false)
    }
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
