package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Verdict

/** Why a transition carries no diff. Exactly one of a diff and a reason is present. */
enum class NoDiff(private val wire: String) {
    /** The earlier grading's code was not kept — a run recorded before 2026-10-07, or code never attached. */
    FROM_CODE_UNKNOWN("fromCodeUnknown"),
    TO_CODE_UNKNOWN("toCodeUnknown"),
    CODE_UNKNOWN("codeUnknown"),

    /** Both sides hold the same code. A repair step shows this only beside a late side. */
    SAME_CODE("sameCode"),

    /** Over [UnifiedDiff.MAX_INPUT_LINES] lines on a side. */
    TOO_LARGE("tooLarge"),
    ;

    fun wireName(): String = wire

    companion object {
        fun unknown(fromMissing: Boolean, toMissing: Boolean): NoDiff {
            if (fromMissing && toMissing) return CODE_UNKNOWN
            if (fromMissing) return FROM_CODE_UNKNOWN
            return TO_CODE_UNKNOWN
        }
    }
}

/** One grading and the next grading of the same problem in the same language, with what changed. */
data class Transition(val from: CodedGrading, val to: CodedGrading, val diff: String?, val noDiff: NoDiff?) {
    init {
        require((diff == null) != (noDiff == null)) { "exactly one of diff and noDiff" }
    }

    /**
     * A repair step: the earlier grading did not pass — an unresolved verdict included, since
     * unresolved is not passed — and something may have changed. Identical code makes no step,
     * **unless a side's code was attached late**: then "identical" may only mean both sides hold
     * the later grading's code, and dropping the step would hide exactly that.
     */
    fun isRepairStep(): Boolean = from.record.verdict != Verdict.PASS && !unchanged()

    /** Whether [diff] was cut at [UnifiedDiff.MAX_LINES]; false when there is no diff to cut. */
    fun isDiffTruncated(): Boolean = diff != null && UnifiedDiff.isTruncated(diff)

    private fun unchanged(): Boolean = noDiff == NoDiff.SAME_CODE && !from.late && !to.late
}

/**
 * The corrections between gradings (spec 2026-10-07 §4.3) — a pure calculator (dev rules §3).
 *
 * It pairs each grading with the **next grading of the same problem in the same language**, run
 * or submit, and diffs their code. It never pairs across a grading whose code is unknown: that
 * pair is returned without a diff and says why. What a change *means* — a habit, a slip, a
 * misread relation — is not decided here or anywhere in the server
 * ([[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]]).
 */
object RepairSteps {
    private const val FROM = "from"
    private const val TO = "to"

    /** The steps a reader studies: failures followed by a change, or by code that cannot be trusted. */
    fun of(timeline: List<CodedGrading>): List<Transition> = transitions(timeline).filter(Transition::isRepairStep)

    /**
     * Every consecutive pair per language, of the timeline [CodeTimeline.of] built, in the time
     * order of each pair's later grading — not grouped by language, since a reader shown them as
     * they are expects a timeline. The sort is stable, so pairs ending at the same instant keep
     * the order of their languages' first gradings.
     */
    fun transitions(timeline: List<CodedGrading>): List<Transition> = timeline
        .groupBy { it.record.language.lowercase() }
        .values
        .flatMap { it.zipWithNext(::between) }
        .sortedBy { it.to.record.ts }

    private fun between(from: CodedGrading, to: CodedGrading): Transition {
        val old = from.code?.text
        val new = to.code?.text
        if (old == null || new == null) return Transition(from, to, null, NoDiff.unknown(old == null, new == null))
        return compared(from, to, linesOf(old), linesOf(new))
    }

    private fun compared(from: CodedGrading, to: CodedGrading, old: List<String>, new: List<String>): Transition {
        if (old == new) return Transition(from, to, null, NoDiff.SAME_CODE)
        if (!UnifiedDiff.fits(old, new)) return Transition(from, to, null, NoDiff.TOO_LARGE)
        return Transition(from, to, UnifiedDiff.of(old, new, FROM, TO), null)
    }

    // runs.jsonl keeps code as fetched; an attempt file has exactly one trailing newline. Compared
    // raw, the same code read from the two places would differ.
    //
    // Every trailing newline is trimmed here (D4), where CodeArtifacts removes only one: a change
    // that only adds trailing blank lines is SAME_CODE here but a diff in an attempt's
    // diffFromPrev. `\r\n` is not normalized - both sides come from the same fetch path.
    private fun linesOf(text: String): List<String> {
        val body = text.trimEnd('\n')
        if (body.isEmpty()) return emptyList()
        return body.split("\n")
    }
}
