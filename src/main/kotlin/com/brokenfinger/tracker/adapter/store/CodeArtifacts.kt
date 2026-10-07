package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.UnifiedDiff
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The code files derived from a record — `Solution.<ext>`, `attempts/NNN.<ext>` and the diff
 * against the previous attempt (design §5.1).
 *
 * **`run` and `submit` are not treated alike** (design §5.1, "How run and submit are handled
 * differently"). `run` is pressed dozens of times while writing code, so it refreshes the
 * solution file and stops there. Only a `submit` owns an attempt number, so only a `submit`
 * gets an attempt copy and a diff; a run that wrote one would overwrite an attempt that is
 * already history.
 *
 * Every write is temp-then-replace via [AtomicStateFile], so a reader — a git commit, an
 * editor, a later re-analysis — never sees half a solution, and writing the same code twice
 * leaves the same bytes.
 */
class CodeArtifacts(recordRoot: Path, private val records: RecordStore) {
    private val layout = RecordLayout(recordRoot)
    private val files = ProblemFiles(layout)

    /** The latest code per language, refreshed on **both** run and submit (design §5.1). */
    fun writeLatest(record: SubmissionRecord, code: String): Path {
        val name = "$SOLUTION.${RecordLayout.extensionOf(record.language)}"
        return written(layout.problemDirectory(record.lessonId, record.title).resolve(name), code)
    }

    /**
     * The attempt copy — `attempts/NNN.<ext>`. Null for a run, which owns no attempt number
     * of its own.
     */
    fun writeAttempt(record: SubmissionRecord, code: String): Path? {
        if (!ownsAttemptFile(record)) return null
        return written(attemptFile(record, record.attempt), code)
    }

    /**
     * A unified diff of [code] against **the previous attempt in the same language**
     * (design §5.1). Numbering is monotonic across languages, so the previous attempt is not
     * necessarily the previous *number*: diffing Java against SQL would be noise rather than
     * "what was missed".
     *
     * The output is capped at [MAX_DIFF_LINES] lines and ends with a truncation marker beyond
     * it, because this string is inlined into every record — an uncapped diff of a large file
     * would bloat `log/submissions.jsonl` on its own.
     *
     * Null rather than an invented diff whenever there is nothing honest to compare against:
     * a run, the first attempt in that language, a previous attempt whose file is missing
     * (diffing against an empty file would report the whole solution as added), unchanged
     * code, or a file too large to diff cheaply.
     *
     * The previous attempt is read through [ProblemFiles], because the diff is inlined into a
     * record line that MCP serves and git pushes: one that is a link out of `problems/` counts as
     * missing, rather than putting whatever it leads to into the log (#354).
     */
    fun diffFromPrev(record: SubmissionRecord, code: String): String? {
        if (!ownsAttemptFile(record)) return null
        val previous = previousInSameLanguage(record) ?: return null
        val before = readAttempt(record, previous) ?: return null
        val after = linesOf(normalized(code))
        if (!UnifiedDiff.fits(before, after)) return tooLarge(before, after)
        return UnifiedDiff.of(before, after, nameOf(record, previous), nameOf(record, record.attempt))
    }

    private fun tooLarge(before: List<String>, after: List<String>): String? {
        logger.warn(
            "Skipped a diff of {} against {} lines — beyond the {} line limit",
            before.size,
            after.size,
            MAX_DIFF_INPUT_LINES,
        )
        return null
    }

    // The previous attempt comes from the submission log, the single authority for attempt
    // numbers (design §4.5). Scanning `attempts/` would make the directory a second source of
    // truth, and the language a record was written in only exists in the log anyway — two
    // languages can share one extension (`mysql` and `oracle` are both `.sql`).
    private fun previousInSameLanguage(record: SubmissionRecord): Int? = records.read()
        .filter { it.lessonId == record.lessonId && it.action == GradingAction.SUBMIT }
        .filter { it.language.equals(record.language, ignoreCase = true) }
        .map { it.attempt }
        .filter { it in FIRST_ATTEMPT until record.attempt }
        .maxOrNull()

    private fun readAttempt(record: SubmissionRecord, attempt: Int): List<String>? {
        val bytes = files.readAllBytes(attemptFile(record, attempt)) ?: return missing(attempt)
        // Decoded with replacement rather than reported: one torn byte must not cost the diff.
        return linesOf(String(bytes, CHARSET))
    }

    private fun missing(attempt: Int): List<String>? {
        logger.warn("Attempt {} has no stored code; recording no diff rather than inventing one", attempt)
        return null
    }

    private fun written(file: Path, code: String): Path {
        AtomicStateFile(file).write(normalized(code))
        return file
    }

    private fun ownsAttemptFile(record: SubmissionRecord): Boolean =
        record.action == GradingAction.SUBMIT && record.attempt >= FIRST_ATTEMPT

    private fun attemptFile(record: SubmissionRecord, attempt: Int): Path =
        layout.attemptFile(record.lessonId, record.title, attempt, record.language)

    /** The name a diff header shows — relative to the problem directory, as git would. */
    private fun nameOf(record: SubmissionRecord, attempt: Int): String =
        "$ATTEMPTS/${attemptFile(record, attempt).fileName}"

    companion object {
        /** The diff is inlined into every record line, so a long one is cut off here. */
        const val MAX_DIFF_LINES = UnifiedDiff.MAX_LINES

        /** Past this the LCS table stops being cheap, and such a file yields no diff at all. */
        const val MAX_DIFF_INPUT_LINES = UnifiedDiff.MAX_INPUT_LINES

        private const val FIRST_ATTEMPT = 1
        private const val SOLUTION = "Solution"
        private const val ATTEMPTS = "attempts"
        private val CHARSET = StandardCharsets.UTF_8
        private val logger = LoggerFactory.getLogger(CodeArtifacts::class.java)

        // Exactly one trailing newline, whether or not the fetched code ended in one: an
        // ordinary text file, and a last line that does not move between attempts.
        private fun normalized(code: String): String = code.trimEnd('\n') + "\n"

        private fun linesOf(text: String): List<String> {
            val body = text.removeSuffix("\n")
            if (body.isEmpty()) return emptyList()
            return body.split("\n")
        }
    }
}
