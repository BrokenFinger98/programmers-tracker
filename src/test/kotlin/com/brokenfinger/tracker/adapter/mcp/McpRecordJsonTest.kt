package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.application.CodedProblem
import com.brokenfinger.tracker.application.ProblemHistory
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.TestcaseSummary
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.CodedGrading
import com.brokenfinger.tracker.domain.calc.LabelledStep
import com.brokenfinger.tracker.domain.calc.NoDiff
import com.brokenfinger.tracker.domain.calc.ProblemLabel
import com.brokenfinger.tracker.domain.calc.Transition
import com.brokenfinger.tracker.domain.calc.UnifiedDiff
import com.brokenfinger.tracker.support.fixtures.aCodedGrading
import com.brokenfinger.tracker.support.fixtures.aKeptCode
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSqlSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aSubmit
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class McpRecordJsonTest {
    @Test
    fun `carries the whole record, testcases included`() {
        val json = McpRecordJson.full(aSubmissionRecord())

        json.shouldContainKey("testcases")
        json.shouldContainKey("diffFromPrev")
        json["lessonId"]!!.jsonPrimitive.int shouldBe 120804
        json["testcases"]!!.jsonArray.size shouldBe 1
    }

    /**
     * The rule the whole projection exists for: a field the SQL path never sends is **absent**,
     * not null and not zero. Writing a zero score would drag every average down silently —
     * the outcome the constitution ranks worst ([[concepts/assumption-vs-measurement]]).
     */
    @Test
    fun `a value that was never recorded is absent, not blanked`() {
        val json = McpRecordJson.full(aSqlSubmissionRecord())

        json.shouldNotContainKey("score")
        json.shouldNotContainKey("rating")
        json.shouldNotContainKey("errorText")
    }

    /** A default is something we know, not something we are missing, so it is written out. */
    @Test
    fun `keeps the defaults that are measurements in their own right`() {
        val json = McpRecordJson.full(aSqlSubmissionRecord())

        json.shouldContainKey("tags")
        json["tags"]!!.jsonArray.shouldContainExactly()
        json["codePending"]!!.jsonPrimitive.booleanOrNull!!.shouldBeFalse()
    }

    @Test
    fun `the history view drops only the three fields that run to kilobytes`() {
        val summary = McpRecordJson.summary(aSubmissionRecord())

        summary.keys.shouldNotContain("testcases")
        summary.keys.shouldNotContain("errorText")
        summary.keys.shouldNotContain("diffFromPrev")
        summary.shouldContainKey("verdict")
        summary.shouldContainKey("tcSummary")
    }

    @Test
    fun `the history view keeps everything else the full view has`() {
        // A record carrying all three heavy fields, so the difference is the drop and
        // not merely a value that happened to be absent.
        val record = aSubmissionRecord(errorText = "Main.java:3: error: ';' expected")

        (McpRecordJson.full(record).keys - McpRecordJson.summary(record).keys)
            .shouldBe(setOf("testcases", "errorText", "diffFromPrev"))
    }

    @Test
    fun `a problem reports its submissions in full and counts them`() {
        val history = ProblemHistory(120804, "two numbers", 0, "intro", 91, listOf("구현"), listOf(aSubmissionRecord()))

        val json = McpRecordJson.problem(history)

        json["lessonId"]!!.jsonPrimitive.int shouldBe 120804
        json["title"]!!.jsonPrimitive.content shouldBe "two numbers"
        json["submissionCount"]!!.jsonPrimitive.int shouldBe 1
        json["submissions"]!!.jsonArray.size shouldBe 1
    }

    /**
     * The array carries every record and the two counts split it, in the words the vault's own
     * page uses. `submissionCount` was the array's length, so `get_problem` answered 15 for a
     * problem `list_problems` called 8 attempts — the same disagreement #235 fixed in the tally,
     * one code path over (#237).
     */
    @Test
    fun `the counts split the array into submits and runs`() {
        val records = listOf(
            aSubmissionRecord(action = GradingAction.SUBMIT),
            aSubmissionRecord(action = GradingAction.RUN),
            aSubmissionRecord(action = GradingAction.RUN),
        )
        val history = ProblemHistory(120804, "two numbers", 0, "intro", 91, listOf("구현"), records)

        val json = McpRecordJson.problem(history)

        json["submissionCount"]!!.jsonPrimitive.int shouldBe 1
        json["runCount"]!!.jsonPrimitive.int shouldBe 2
        json["submissions"]!!.jsonArray.size shouldBe 3
    }

    /**
     * The question every other field on this surface was an answer *about* (#278). Storing the
     * statement helped the vault; until it reached here, an AI still knew that testcase 3 failed
     * and nothing about what was wanted.
     */
    @Test
    fun `a problem carries the statement that was captured for it`() {
        val history = aProblemHistory(statement = "정수 두 개를 더해 return 하세요.")

        McpRecordJson.problem(history)["statement"]!!.jsonPrimitive.content shouldBe "정수 두 개를 더해 return 하세요."
    }

    /** Written out, so a reordering of the problem's keys is a change a test notices rather than one a client does. */
    @Test
    fun `a problem's keys come in this order, the statement between the tags and the counts`() {
        val keys = McpRecordJson.problem(aProblemHistory(statement = "정수 두 개를 더해 return 하세요.")).keys.toList()

        keys shouldBe listOf(
            "lessonId",
            "title",
            "level",
            "part",
            "acceptanceRate",
            "tags",
            "statement",
            "submissionCount",
            "runCount",
            "submissions",
        )
    }

    /** Absent, never `""` — an empty string reads as a problem with no description. */
    @Test
    fun `a problem with no captured statement carries no statement key`() {
        McpRecordJson.problem(aProblemHistory()).shouldNotContainKey("statement")
    }

    private fun aProblemHistory(
        statement: String? = null,
        submissions: List<SubmissionRecord> = listOf(aSubmissionRecord()),
    ) = ProblemHistory(
        lessonId = 120804,
        title = "two numbers",
        level = 0,
        part = "intro",
        acceptanceRate = 91,
        tags = listOf("구현"),
        submissions = submissions,
        statement = statement,
    )

    /** A problem with no recorded title returns no title. Not "Unknown", not an empty string. */
    @Test
    fun `a problem with nothing recorded carries no metadata keys at all`() {
        val json = McpRecordJson.problem(ProblemHistory(120804, null, null, null, null, emptyList(), emptyList()))

        json.shouldNotContainKey("title")
        json.shouldNotContainKey("level")
        json.shouldNotContainKey("part")
        json.shouldNotContainKey("acceptanceRate")
        json["submissionCount"]!!.jsonPrimitive.int shouldBe 0
        json["runCount"]!!.jsonPrimitive.int shouldBe 0
        json["submissions"]!!.jsonArray.size shouldBe 0
    }

    /**
     * The summary drops `errorText` for weight, which without this field would trim the
     * explanation off the exact record a user asks an AI about — "why is this UNKNOWN when
     * my screen said 100?" (#74).
     */
    @Test
    fun `a cached-result unknown keeps its reason in the summary`() {
        val cached = aSubmissionRecord(
            outcome = Outcome.UNKNOWN,
            verdict = null,
            errorText = "같은 코드로 채점한 결과가 있습니다.",
        )

        val summary = McpRecordJson.summary(cached)

        summary["unknownReason"]?.jsonPrimitive?.content shouldBe "cached result"
        summary.containsKey("errorText") shouldBe false
    }

    @Test
    fun `an unexplained unknown carries no reason field at all`() {
        val odd = aSubmissionRecord(outcome = Outcome.UNKNOWN, verdict = null, errorText = "서버 점검 중입니다.")

        McpRecordJson.summary(odd).containsKey("unknownReason") shouldBe false
    }

    // repair steps ---------------------------------------------------------------------------

    @Test
    fun `a repair step carries what failed, what followed, and why there is no diff`() {
        val tuple = "(1054, \"Unknown column 'x' in 'field list'\")"
        val failed = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.COMPILE_ERROR)
            .copy(errorText = tuple, testcases = listOf(aTestcaseResult(passed = false, msg = tuple)))
        val followed = aSubmit(at = "2026-10-07T10:00:05+09:00", verdict = Verdict.PASS)
        val step = LabelledStep(
            ProblemLabel(lessonId = 120804, title = "t", level = 2, part = "SELECT"),
            Transition(aCodedGrading(failed, late = true), aCodedGrading(followed), null, NoDiff.SAME_CODE),
        )

        val json = McpRecordJson.repairSteps(listOf(step)).single().jsonObject

        json["lessonId"]!!.jsonPrimitive.int shouldBe 120804
        json["title"]!!.jsonPrimitive.content shouldBe "t"
        json["part"]!!.jsonPrimitive.content shouldBe "SELECT"
        json["level"]!!.jsonPrimitive.int shouldBe 2
        json["language"]!!.jsonPrimitive.content shouldBe "java"
        json["noDiff"]!!.jsonPrimitive.content shouldBe "sameCode"
        json.shouldNotContainKey("diff")
        json.shouldNotContainKey("diffTruncated")
        val from = json["from"]!!.jsonObject
        from["recordId"]!!.jsonPrimitive.content shouldBe failed.recordId()
        from["ts"]!!.jsonPrimitive.content shouldBe "2026-10-07T10:00:00+09:00"
        from["action"]!!.jsonPrimitive.content shouldBe "run"
        from["outcome"]!!.jsonPrimitive.content shouldBe "JUDGED"
        from["verdict"]!!.jsonPrimitive.content shouldBe "COMPILE_ERROR"
        from["errorText"]!!.jsonPrimitive.content shouldBe tuple
        from["failedMessage"]!!.jsonPrimitive.content shouldBe tuple
        from["failedCases"]!!.jsonPrimitive.int shouldBe 1
        from["totalCases"]!!.jsonPrimitive.int shouldBe 1
        from.shouldNotContainKey("casesComplete")
        from["codeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
        val to = json["to"]!!.jsonObject
        to["action"]!!.jsonPrimitive.content shouldBe "submit"
        to["verdict"]!!.jsonPrimitive.content shouldBe "PASS"
        to.shouldNotContainKey("codeLate")
        to.shouldNotContainKey("errorText")
        to.shouldNotContainKey("failedCases")
    }

    /** Absent is not zero: an unresolved grading has no verdict key, and says how it ended instead. */
    @Test
    fun `an unresolved side has no verdict, only its outcome`() {
        val unresolved = aRun(at = "2026-10-07T10:00:00+09:00", verdict = null, outcome = Outcome.UNKNOWN)
        val step = aStep(from = unresolved, diff = "d")

        val json = McpRecordJson.repairSteps(listOf(step)).single().jsonObject

        json["from"]!!.jsonObject.shouldNotContainKey("verdict")
        json["from"]!!.jsonObject["outcome"]!!.jsonPrimitive.content shouldBe "UNKNOWN"
        json["diff"]!!.jsonPrimitive.content shouldBe "d"
        json.shouldNotContainKey("noDiff")
    }

    @Test
    fun `a problem with no recorded label fields has none of those keys on its steps`() {
        val step = LabelledStep(ProblemLabel(lessonId = 7, title = null, level = null, part = null), aTransition())

        val json = McpRecordJson.repairSteps(listOf(step)).single().jsonObject

        json["lessonId"]!!.jsonPrimitive.int shouldBe 7
        json.shouldNotContainKey("title")
        json.shouldNotContainKey("level")
        json.shouldNotContainKey("part")
    }

    /** The case a reader cannot otherwise tell from "the first one": testcases arrive in any order. */
    @Test
    fun `the failed message is the lowest failing case's, and absent when that case said nothing`() {
        val said = aRun(at = "2026-10-07T10:00:00+09:00").copy(
            testcases = listOf(
                aTestcaseResult(id = 3, passed = false, msg = "third"),
                aTestcaseResult(id = 1, passed = true, msg = "ok"),
                aTestcaseResult(id = 2, passed = false, msg = "second"),
            ),
        )
        val silent = aRun(at = "2026-10-07T10:00:00+09:00").copy(
            testcases = listOf(aTestcaseResult(id = 1, passed = false, msg = null)),
        )

        fromSideOf(said)["failedMessage"]!!.jsonPrimitive.content shouldBe "second"
        fromSideOf(silent).shouldNotContainKey("failedMessage")
    }

    /** Decided in the domain, so a reader never parses the marker out of the diff text. */
    @Test
    fun `a step whose diff was cut at the cap says so`() {
        val cut = "--- a/from\n+++ b/to\n@@ -0,0 +1,500 @@\n+n1\n${UnifiedDiff.TRUNCATION_MARKER}"

        val json = McpRecordJson.repairSteps(listOf(aStep(diff = cut))).single().jsonObject

        json["diffTruncated"]!!.jsonPrimitive.booleanOrNull shouldBe true
        json["diff"]!!.jsonPrimitive.content shouldBe cut
    }

    @Test
    fun `a step whose diff fits carries no truncation flag, and neither does one with no diff`() {
        val fits = McpRecordJson.repairSteps(listOf(aStep(diff = "--- a/from\n+++ b/to"))).single().jsonObject
        val none = McpRecordJson.repairSteps(listOf(aStep(diff = null, noDiff = NoDiff.CODE_UNKNOWN)))
            .single().jsonObject

        fits.shouldNotContainKey("diffTruncated")
        none.shouldNotContainKey("diffTruncated")
        none["noDiff"]!!.jsonPrimitive.content shouldBe "codeUnknown"
    }

    /**
     * `failedCases` and `totalCases` count the cases that arrived. A grading whose stream delivered fewer
     * than it announced must not read as a full set (`TestcaseSummary`'s own invariant), and
     * `get_problem` already says so through `tcSummary.complete`; here the flag rides beside the counts.
     */
    @Test
    fun `a failing side says when its case counts are of a partly observed grading`() {
        val partial = aRun(at = "2026-10-07T10:00:00+09:00")
            .copy(tcSummary = TestcaseSummary(total = 1, passed = 0, failed = 1, complete = false))

        val from = fromSideOf(partial)

        from["failedCases"]!!.jsonPrimitive.int shouldBe 1
        from["totalCases"]!!.jsonPrimitive.int shouldBe 1
        from["casesComplete"]!!.jsonPrimitive.booleanOrNull shouldBe false
    }

    /** Absent, not `true`: the flag exists to say what is missing, and nothing is. */
    @Test
    fun `a failing side whose cases all arrived carries no completeness flag`() {
        fromSideOf(aRun(at = "2026-10-07T10:00:00+09:00")).shouldNotContainKey("casesComplete")
    }

    /** A compile error reports no cases at all: the clearest counts that are not a full set. */
    @Test
    fun `a compile error reports no cases and says that is not the full set`() {
        val compile = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.COMPILE_ERROR)
            .copy(testcases = emptyList(), tcSummary = TestcaseSummary.of(emptyList(), complete = false))

        val from = fromSideOf(compile)

        from["failedCases"]!!.jsonPrimitive.int shouldBe 0
        from["totalCases"]!!.jsonPrimitive.int shouldBe 0
        from["casesComplete"]!!.jsonPrimitive.booleanOrNull shouldBe false
    }

    /** The later side carries no counts, so there is nothing there for the flag to qualify. */
    @Test
    fun `the later side carries no case counts and so no completeness flag`() {
        val partial = aRun(at = "2026-10-07T10:00:05+09:00")
            .copy(tcSummary = TestcaseSummary(total = 1, passed = 0, failed = 1, complete = false))

        val json = McpRecordJson.repairSteps(listOf(aStep(to = partial))).single().jsonObject

        json["to"]!!.jsonObject.shouldNotContainKey("failedCases")
        json["to"]!!.jsonObject.shouldNotContainKey("casesComplete")
    }

    // get_problem include ---------------------------------------------------------------------

    /** No include is the answer it always was, key for key and position for position. */
    @Test
    fun `a problem asked for nothing extra is the problem without a coded timeline`() {
        val run = aRun(at = "2026-10-07T09:59:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val history = aProblemHistory(submissions = listOf(submit, run))
        val coded = CodedProblem(listOf(aCodedGrading(run, "a"), aCodedGrading(submit, "b")), emptyList())

        val json = McpRecordJson.problem(history, coded, emptySet())

        json shouldBe McpRecordJson.problem(history)
        json.keys.toList() shouldBe McpRecordJson.problem(history).keys.toList()
    }

    @Test
    fun `include code puts a submit's code on the submit and nothing on a run`() {
        val run = aRun(at = "2026-10-07T09:59:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val coded = CodedProblem(
            listOf(aCodedGrading(run, "select 0"), aCodedGrading(submit, "select 1\n")),
            emptyList(),
        )

        val items = itemsOf(McpRecordJson.problem(aProblemHistory(submissions = listOf(submit, run)), coded, CODE))

        items[0]["code"]!!.jsonPrimitive.content shouldBe "select 1\n"
        CODE_KEYS.forEach { items[1].shouldNotContainKey(it) }
    }

    @Test
    fun `include runs puts a run's code, when it was fetched and the diff into it, and nothing on a submit`() {
        val run = aRun(at = "2026-10-07T10:00:05+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val kept = aKeptCode("b", fetchedAt = "2026-10-07T10:00:06+09:00")
        val coded = CodedProblem(
            listOf(aCodedGrading(submit, "a"), CodedGrading(run, kept, late = false)),
            listOf(Transition(aCodedGrading(submit, "a"), CodedGrading(run, kept, late = false), "the diff", null)),
        )

        val items = itemsOf(McpRecordJson.problem(aProblemHistory(submissions = listOf(run, submit)), coded, RUNS))

        items[0]["code"]!!.jsonPrimitive.content shouldBe "b"
        items[0]["codeFetchedAt"]!!.jsonPrimitive.content shouldBe "2026-10-07T10:00:06+09:00"
        items[0]["diffFromPrevGrading"]!!.jsonPrimitive.content shouldBe "the diff"
        listOf("codeLate", "diffTruncated", "noDiff").forEach { items[0].shouldNotContainKey(it) }
        CODE_KEYS.forEach { items[1].shouldNotContainKey(it) }
    }

    /** Absent, not `false`: the flag says that the check found a problem, and not finding one is silence. */
    @Test
    fun `a run's code is marked late only when the check found it so`() {
        val late = aRun(at = "2026-10-07T10:00:00+09:00")
        val onTime = aRun(at = "2026-10-07T10:00:05+09:00")
        val coded = CodedProblem(
            listOf(CodedGrading(late, aKeptCode("a"), late = true), aCodedGrading(onTime, "b")),
            emptyList(),
        )

        val items = itemsOf(McpRecordJson.problem(aProblemHistory(submissions = listOf(onTime, late)), coded, RUNS))

        items[1]["codeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
        items[0].shouldNotContainKey("codeLate")
    }

    /**
     * The diff is on this item and the late code is on the one before it, so a reader of this item would
     * never see the doubt: the item that carries the diff says that the earlier side's code was late.
     */
    @Test
    fun `a run's diff says when the code it was taken from was late`() {
        val before = aRun(at = "2026-10-07T10:00:00+09:00")
        val after = aRun(at = "2026-10-07T10:00:05+09:00")
        val late = CodedGrading(before, aKeptCode("a", fetchedAt = "2026-10-07T10:00:06+09:00"), late = true)
        val onTime = aCodedGrading(after, "b")
        val coded = CodedProblem(listOf(late, onTime), listOf(Transition(late, onTime, "the diff", null)))

        val items = itemsOf(McpRecordJson.problem(aProblemHistory(submissions = listOf(after, before)), coded, RUNS))

        items[0]["fromCodeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
        items[0].shouldNotContainKey("codeLate")
        items[1]["codeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
        items[1].shouldNotContainKey("fromCodeLate")
    }

    /** Absent, not `false`; and a run's own late code says nothing about the code its diff was taken from. */
    @Test
    fun `a run whose earlier side was on time carries no fromCodeLate, however its own code fared`() {
        val before = aRun(at = "2026-10-07T10:00:00+09:00")
        val after = aRun(at = "2026-10-07T10:00:05+09:00")
        val onTime = aCodedGrading(before, "a")
        val late = CodedGrading(after, aKeptCode("b", fetchedAt = "2026-10-07T10:00:09+09:00"), late = true)
        val coded = CodedProblem(listOf(onTime, late), listOf(Transition(onTime, late, "the diff", null)))

        val item = itemsOf(McpRecordJson.problem(aProblemHistory(submissions = listOf(after, before)), coded, RUNS))[0]

        item["codeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
        item.shouldNotContainKey("fromCodeLate")
    }

    /** A grading whose code was not kept has no `code`, and the step into it says why there is no diff. */
    @Test
    fun `a run with no kept code has no code keys, and keeps the reason its step has no diff`() {
        val before = aRun(at = "2026-10-07T10:00:00+09:00")
        val after = aRun(at = "2026-10-07T10:00:05+09:00")
        val unkept = aCodedGrading(after, code = null)
        val coded = CodedProblem(
            listOf(aCodedGrading(before, "a"), unkept),
            listOf(Transition(aCodedGrading(before, "a"), unkept, null, NoDiff.TO_CODE_UNKNOWN)),
        )

        val item = itemsOf(McpRecordJson.problem(aProblemHistory(submissions = listOf(after, before)), coded, RUNS))[0]

        item["noDiff"]!!.jsonPrimitive.content shouldBe "toCodeUnknown"
        listOf("code", "codeFetchedAt", "codeLate", "diffFromPrevGrading", "diffTruncated").forEach {
            item.shouldNotContainKey(it)
        }
    }

    /** The flag is the one a step uses, though its diff has another name here: one word for one thing. */
    @Test
    fun `a run's diff cut at the cap is flagged diffTruncated, beside diffFromPrevGrading`() {
        val before = aRun(at = "2026-10-07T10:00:00+09:00")
        val after = aRun(at = "2026-10-07T10:00:05+09:00")
        val cut = "--- a/from\n+++ b/to\n@@ -0,0 +1,500 @@\n+n1\n${UnifiedDiff.TRUNCATION_MARKER}"
        val coded = CodedProblem(
            listOf(aCodedGrading(before, "a"), aCodedGrading(after, "b")),
            listOf(Transition(aCodedGrading(before, "a"), aCodedGrading(after, "b"), cut, null)),
        )

        val item = itemsOf(McpRecordJson.problem(aProblemHistory(submissions = listOf(after, before)), coded, RUNS))[0]

        item["diffFromPrevGrading"]!!.jsonPrimitive.content shouldBe cut
        item["diffTruncated"]!!.jsonPrimitive.booleanOrNull shouldBe true
    }

    /** A record the timeline does not hold is left as the default answer has it, not failed on. */
    @Test
    fun `an item the coded timeline does not hold gets no code keys`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:05+09:00")

        val items = itemsOf(
            McpRecordJson.problem(
                aProblemHistory(submissions = listOf(submit, run)),
                CodedProblem(emptyList(), emptyList()),
                ProblemInclude.entries.toSet(),
            ),
        )

        items.forEach { item -> CODE_KEYS.forEach { item.shouldNotContainKey(it) } }
    }

    /** What was there keeps its place; the code lands after it, and `submissions` stays where it was. */
    @Test
    fun `include adds its keys after the record's own, and the problem's keys keep their order`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val history = aProblemHistory(submissions = listOf(run))
        val coded = CodedProblem(listOf(aCodedGrading(run, "a")), emptyList())

        val json = McpRecordJson.problem(history, coded, RUNS)

        json.keys.toList() shouldBe McpRecordJson.problem(history).keys.toList()
        val plain = McpRecordJson.full(run).keys.toList()
        itemsOf(json)[0].keys.toList().take(plain.size) shouldBe plain
    }

    private fun itemsOf(problem: JsonObject): List<JsonObject> =
        problem["submissions"]!!.jsonArray.map { it.jsonObject }

    private fun aStep(
        from: SubmissionRecord = aRun(at = "2026-10-07T10:00:00+09:00"),
        diff: String? = "d",
        noDiff: NoDiff? = null,
        to: SubmissionRecord = aRun(at = "2026-10-07T10:00:05+09:00"),
    ): LabelledStep = LabelledStep(
        ProblemLabel(lessonId = 120804, title = null, level = null, part = null),
        Transition(aCodedGrading(from), aCodedGrading(to), diff, noDiff),
    )

    private fun aTransition(): Transition = aStep().step

    private fun fromSideOf(record: SubmissionRecord): JsonObject =
        McpRecordJson.repairSteps(listOf(aStep(from = record))).single().jsonObject["from"]!!.jsonObject

    private companion object {
        val CODE = setOf(ProblemInclude.CODE)
        val RUNS = setOf(ProblemInclude.RUNS)

        /** Every key `include` can add to an item; the default answer carries none of them. */
        val CODE_KEYS = listOf(
            "code",
            "codeFetchedAt",
            "codeLate",
            "diffFromPrevGrading",
            "diffTruncated",
            "noDiff",
            "fromCodeLate",
        )
    }
}
