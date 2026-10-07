package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.application.ProblemHistory
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.TestcaseSummary
import com.brokenfinger.tracker.domain.calc.BrowsedProblem
import com.brokenfinger.tracker.domain.calc.CodedGrading
import com.brokenfinger.tracker.domain.calc.LabelledStep
import com.brokenfinger.tracker.domain.calc.ProblemLabel
import com.brokenfinger.tracker.domain.calc.ReviewItem
import com.brokenfinger.tracker.domain.calc.SlowPass
import com.brokenfinger.tracker.domain.calc.Transition
import com.brokenfinger.tracker.domain.calc.UnknownReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * How a stored record reaches an AI.
 *
 * **A field that was never recorded is absent, not blank and not zero.** That is the whole
 * point of `explicitNulls = false`: a problem whose title we never captured has no `title`
 * key, so a reader cannot mistake a gap in our observation for a measurement we made
 * ([[concepts/assumption-vs-measurement]]). Defaults *are* encoded, because `tags: []` and
 * `codePending: false` are things we know rather than things we are missing.
 *
 * The projection is derived from the stored record rather than re-declared, so a field
 * added to design §5.2 appears here without a second edit — and cannot silently go missing.
 */
object McpRecordJson {
    private val format = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    fun full(record: SubmissionRecord): JsonObject = withReason(record, format.encodeToJsonElement(record).jsonObject)

    /**
     * The history view. Drops only the three fields that each run to kilobytes — a full
     * testcase list, a compiler dump, a diff — because `submissions` may answer with the
     * whole log and `get_problem` is where the detail lives.
     */
    fun summary(record: SubmissionRecord): JsonObject = JsonObject(full(record) - HEAVY)

    /**
     * The classified reason an UNKNOWN is unknown (#74). Carried as its own short field
     * because the summary view drops `errorText` for weight — without this, the one record a
     * user will ask an AI about ("why is this UNKNOWN when my screen said 100?") is the one
     * whose explanation was trimmed. Absent when unmeasured, per the classifier.
     */
    private fun withReason(record: SubmissionRecord, encoded: JsonObject): JsonObject {
        val reason = UnknownReason.of(record.outcome, record.errorText) ?: return encoded
        return JsonObject(encoded + ("unknownReason" to format.encodeToJsonElement(reason.label)))
    }

    fun summaries(records: List<SubmissionRecord>): JsonArray = JsonArray(records.map(::summary))

    /**
     * One problem in full — every record it has, and **two counts that split them.**
     *
     * `submissionCount` used to be the array's length, so `get_problem` answered 15 where
     * `list_problems` called the same problem 8 attempts (#237): the array holds runs too, and a
     * run is not an attempt (design §5.1). The words are the vault's — `problems/<id>/README.md`
     * has carried `attempts` and `runCount` side by side since it was written, and two views of
     * one problem should not need two vocabularies.
     *
     * The array keeps every record, because `get_problem` is where the compiler output lives and
     * that only comes from the run path.
     */
    fun problem(history: ProblemHistory): JsonObject = buildJsonObject {
        put("lessonId", history.lessonId)
        history.title?.let { put("title", it) }
        history.level?.let { put("level", it) }
        history.part?.let { put("part", it) }
        history.acceptanceRate?.let { put("acceptanceRate", it) }
        put("tags", format.encodeToJsonElement(history.tags))
        // What the problem asked, which nothing on this surface could answer before (#278).
        // Absent when none was captured — never an empty string, which would read as a problem
        // that has no description rather than one we never fetched.
        history.statement?.let { put("statement", it) }
        put("submissionCount", history.submissions.count { it.isSubmission() })
        put("runCount", history.submissions.count { !it.isSubmission() })
        put("submissions", JsonArray(history.submissions.map(::full)))
    }

    /**
     * The catalog browse (#100). A field the snapshot does not carry is **left out**, the
     * same rule the record serializers follow: an absent level and a level of zero mean
     * different things, and only one of them was measured.
     */
    /**
     * The schedule and every fact behind it. Absent stays absent: a sensor field is omitted
     * rather than written as null, because `"sawQuestions": null` reads like an observation
     * and "we were not watching" is not one (#132).
     */
    fun reviewItems(due: List<ReviewItem>): JsonArray = JsonArray(due.map(::reviewItem))

    private fun reviewItem(item: ReviewItem): JsonObject = buildJsonObject {
        put("lessonId", item.lessonId)
        put("title", item.title)
        // Half the identity, not decoration: one problem appears once per language it was
        // passed in, and a reader seeing the same lessonId twice needs this to tell them apart.
        put("language", item.language)
        item.level?.let { put("level", it) }
        put("passedAt", item.passedAt.toString())
        put("attempts", item.attempts)
        item.sawQuestions?.let { put("sawQuestions", it) }
        item.focusedSec?.let { put("focusedSec", it) }
        put("confidence", item.confidence.wireName())
        put("dueAt", item.dueAt.toString())
        put("overdueDays", item.overdueDays)
    }

    /** Milliseconds as a number, not a string: the record keeps the judge's own spelling, but
     * a caller comparing speeds should not have to parse it back. */
    fun slowPasses(slow: List<SlowPass>): JsonArray = JsonArray(slow.map(::slowPass))

    private fun slowPass(pass: SlowPass): JsonObject = buildJsonObject {
        put("lessonId", pass.lessonId)
        put("title", pass.title)
        pass.level?.let { put("level", it) }
        put("tags", JsonArray(pass.tags.map(::JsonPrimitive)))
        put("language", pass.language)
        put("passedAt", pass.passedAt.toString())
        put("slowestMs", pass.slowestMs)
        put("slowestCaseId", pass.slowestCaseId)
        put("timedCases", pass.timedCases)
    }

    fun problems(found: List<BrowsedProblem>): JsonArray = JsonArray(
        found.map { problem ->
            buildJsonObject {
                put("lessonId", problem.lessonId)
                put("title", problem.title)
                problem.level?.let { put("level", it) }
                problem.part?.let { put("part", it) }
                problem.acceptanceRate?.let { put("acceptanceRate", it) }
                put("tags", format.encodeToJsonElement(problem.tags))
                put("status", problem.status.wireName())
                put("attempts", problem.attempts)
            }
        },
    )

    /**
     * Repair steps (spec 2026-10-07 §4.3): facts on both sides, and the diff or the reason there is
     * none. Absent stays absent — a problem recorded before the catalog was consulted has no `part`,
     * an unresolved grading has no `verdict`, and a step with a diff has no `noDiff`.
     */
    fun repairSteps(steps: List<LabelledStep>): JsonArray = JsonArray(steps.map(::repairStep))

    private fun repairStep(labelled: LabelledStep): JsonObject = buildJsonObject {
        labelOf(labelled.problem)
        put("language", labelled.step.to.record.language)
        put("from", failedSide(labelled.step.from))
        put("to", sideOf(labelled.step.to))
        diffOf(labelled.step, "diff")
    }

    private fun JsonObjectBuilder.labelOf(problem: ProblemLabel) {
        put("lessonId", problem.lessonId)
        problem.title?.let { put("title", it) }
        problem.part?.let { put("part", it) }
        problem.level?.let { put("level", it) }
    }

    // The earlier side also says what failed: the error text in full (it is the step's core
    // evidence), the first failing case's own message, and how many cases failed.
    private fun failedSide(grading: CodedGrading): JsonObject {
        val record = grading.record
        val failure = buildJsonObject {
            record.errorText?.let { put("errorText", it) }
            firstFailedMessage(record)?.let { put("failedMessage", it) }
            casesOf(record.tcSummary)
        }
        return JsonObject(sideOf(grading) + failure)
    }

    // The counts are of the cases that arrived. `casesComplete: false` is written only when some never
    // did — a compile error reports none — so a partly observed grading cannot read as the full set
    // (TestcaseSummary's own invariant), and a whole one carries no flag.
    private fun JsonObjectBuilder.casesOf(summary: TestcaseSummary) {
        put("failedCases", summary.failed)
        put("totalCases", summary.total)
        if (!summary.complete) put("casesComplete", false)
    }

    // `codeLate` only when the check found it late; a side with no fetch time was never checked.
    private fun sideOf(grading: CodedGrading): JsonObject = buildJsonObject {
        put("recordId", grading.record.recordId())
        put("ts", isoOf(grading.record.ts))
        put("action", grading.record.action.name.lowercase())
        put("outcome", grading.record.outcome.name)
        grading.record.verdict?.let { put("verdict", it.name) }
        if (grading.late) put("codeLate", true)
    }

    private fun firstFailedMessage(record: SubmissionRecord): String? =
        record.testcases.sortedBy { it.id }.firstOrNull { it.hasFailed() }?.msg

    // `diffTruncated` is decided by the domain and written only when true, so a reader never has to
    // parse the cap's marker out of the diff text.
    private fun JsonObjectBuilder.diffOf(transition: Transition, key: String) {
        transition.diff?.let { put(key, it) }
        if (transition.isDiffTruncated()) put("diffTruncated", true)
        transition.noDiff?.let { put("noDiff", it.wireName()) }
    }

    private fun isoOf(at: OffsetDateTime): String = at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private val HEAVY = setOf("testcases", "errorText", "diffFromPrev")
}
