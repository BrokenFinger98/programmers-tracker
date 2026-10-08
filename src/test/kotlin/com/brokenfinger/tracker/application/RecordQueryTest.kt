package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileGradingCodes
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.KeptCode
import com.brokenfinger.tracker.domain.calc.LabelledStep
import com.brokenfinger.tracker.domain.calc.NoDiff
import com.brokenfinger.tracker.domain.calc.ProblemLabel
import com.brokenfinger.tracker.domain.calc.Since
import com.brokenfinger.tracker.domain.calc.TallyBucket
import com.brokenfinger.tracker.domain.calc.TallyGroup
import com.brokenfinger.tracker.support.fixtures.aPartialRecordLine
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aRepairStepFilter
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aSubmit
import com.brokenfinger.tracker.support.fixtures.aTornRecordLine
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

class RecordQueryTest {
    @TempDir
    lateinit var root: Path

    // The shape a user has on day one: nothing has been recorded, and no file exists at all.
    @Test
    fun `an empty record repository answers empty rather than failing`() {
        val query = aRecordRepository(root).query()

        query.history().shouldBeEmpty()
        query.submissions(since = null, verdict = null).shouldBeEmpty()
        query.tally(TallyGroup.VERDICT).shouldBeEmpty()
    }

    @Test
    fun `a problem with nothing recorded answers with an empty history, not an error`() {
        val problem = aRecordRepository(root).query().problem(120804)

        problem.lessonId shouldBe 120804
        problem.submissions.shouldBeEmpty()
        problem.title.shouldBeNull()
        problem.level.shouldBeNull()
        problem.tags.shouldBeEmpty()
    }

    @Test
    fun `reads the log newest first`() {
        val query = aRecordRepository(root).containing(
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-01T10:00:00+09:00")),
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-03T10:00:00+09:00")),
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-02T10:00:00+09:00")),
        ).query()

        query.history().map { it.ts.dayOfMonth }.shouldContainExactly(3, 2, 1)
    }

    /**
     * The failure the writer's design accepts and the reader must absorb: a crash mid-append
     * leaves a torn final line, and refusing the file over it would cost every record before
     * it — a grading Programmers has already broadcast can never be fetched again
     * ([[decisions/2026-08-05-write-serialization]] decision 3).
     */
    @Test
    fun `a torn final line does not fail the read`() {
        val query = aRecordRepository(root)
            .containing(aSubmissionRecord(lessonId = 120804), aSubmissionRecord(lessonId = 131528))
            .tornBy(aTornRecordLine())
            .query()

        query.history().map { it.lessonId }.shouldContainExactly(131528, 120804)
    }

    @Test
    fun `a torn final line leaves the tools answering normally`() {
        val repository = aRecordRepository(root)
            .containing(aSubmissionRecord(verdict = Verdict.PASS))
            .tornBy(aTornRecordLine())

        repository.query().tally(TallyGroup.VERDICT).single().key shouldBe "PASS"
        repository.query().problem(120804).submissions.size shouldBe 1
    }

    /** The other short read: a line the log parser accepts but that is not a whole record. */
    @Test
    fun `a line that is not a full record is skipped, and the rest survive`() {
        val repository = aRecordRepository(root).containing(aSubmissionRecord(lessonId = 131528))
        repository.store().append(aPartialRecordLine())

        repository.query().history().map { it.lessonId }.shouldContainExactly(131528)
    }

    /** Two records in the same second still come back newest-first, by log order. */
    @Test
    fun `breaks a timestamp tie by the order the log was written in`() {
        val same = OffsetDateTime.parse("2026-08-04T14:23:01+09:00")
        val query = aRecordRepository(root).containing(
            aSubmissionRecord(ts = same, attempt = 1),
            aSubmissionRecord(ts = same, attempt = 2),
            aSubmissionRecord(ts = same, attempt = 3),
        ).query()

        query.history().map { it.attempt }.shouldContainExactly(3, 2, 1)
    }

    @Test
    fun `narrows the history by date and verdict`() {
        val query = aRecordRepository(root).containing(
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-07-20T10:00:00+09:00"), verdict = Verdict.PASS),
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-03T10:00:00+09:00"), verdict = Verdict.WRONG),
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-04T10:00:00+09:00"), verdict = Verdict.PASS),
        ).query()

        query.submissions(Since.Day(LocalDate.of(2026, 8, 1)), Verdict.PASS)
            .map { it.ts.dayOfMonth }.shouldContainExactly(4)
    }

    @Test
    fun `gathers every submission against one problem`() {
        val query = aRecordRepository(root).containing(
            aSubmissionRecord(lessonId = 120804, attempt = 1),
            aSubmissionRecord(lessonId = 131528, attempt = 1),
            aSubmissionRecord(lessonId = 120804, attempt = 2),
        ).query()

        query.problem(120804).submissions.map { it.attempt }.shouldContainExactly(2, 1)
    }

    /**
     * Catalog metadata arrives late and unevenly. The newest record that actually carries a
     * field wins, so one submission captured before the catalog was consulted cannot erase
     * a title we already know.
     */
    @Test
    fun `takes each catalog field from the newest record that carries it`() {
        val query = aRecordRepository(root).containing(
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-01T10:00:00+09:00"), title = "two numbers", level = 0),
            aSubmissionRecord(ts = OffsetDateTime.parse("2026-08-02T10:00:00+09:00"), title = "", level = null),
        ).query()

        val problem = query.problem(120804)

        problem.title shouldBe "two numbers"
        problem.level shouldBe 0
    }

    @Test
    fun `a problem whose title was never recorded has no title`() {
        val query = aRecordRepository(root)
            .containing(aSubmissionRecord(title = "", part = "", tags = emptyList(), level = null))
            .query()

        val problem = query.problem(120804)

        problem.title.shouldBeNull()
        problem.part.shouldBeNull()
        problem.level.shouldBeNull()
        problem.tags.shouldBeEmpty()
    }

    @Test
    fun `counts an unresolved grading without giving it a verdict`() {
        val query = aRecordRepository(root).containing(
            aSubmissionRecord(verdict = Verdict.PASS),
            aSubmissionRecord(outcome = Outcome.INCOMPLETE, verdict = null),
        ).query()

        val buckets = query.tally(TallyGroup.VERDICT)

        buckets.sumOf { it.count } shouldBe 2
        buckets.last().key.shouldBeNull()
    }

    /**
     * The composition is where the defect lived: the calculator was handed [RecordQuery.history],
     * which is every record, and counted the runs in it (#235). The rule is now inside
     * `SubmissionTally`, so this pins the wiring rather than the rule — `submissions` keeps
     * returning both, which is what it says it does, and only the tally drops the run.
     */
    @Test
    fun `the tally counts submissions where the history it reads holds runs too`() {
        val query = aRecordRepository(root).containing(
            aSubmissionRecord(action = GradingAction.SUBMIT, verdict = Verdict.PASS),
            aSubmissionRecord(action = GradingAction.RUN, verdict = Verdict.COMPILE_ERROR),
        ).query()

        query.history() shouldHaveSize 2
        query.submissions(since = null, verdict = null) shouldHaveSize 2
        query.tally(TallyGroup.VERDICT).shouldContainExactly(TallyBucket("PASS", null, 1, null))
    }

    // Corrections ------------------------------------------------------------------------
    //
    // Stage 3 attaches the code after the record is already durable, and the log is
    // append-only, so the correction is a second line carrying the same capture key
    // ([[decisions/2026-08-06-record-corrections-by-append]]). Every read has to resolve to
    // the newest line per key. These are the shapes that go wrong when it does not, and they
    // are here rather than only in RecordHistoryTest because the defect they prevent is not
    // in the resolver — it is in a reader that forgets to use it.

    @Test
    fun `a corrected submission is one submission, not two`() {
        val pending = aSubmissionRecord(codePending = true, codePath = null)
        val query = aRecordRepository(root)
            .containing(pending, pending.copy(codePending = false, codePath = "problems/120804/attempts/001.java"))
            .query()

        query.history() shouldHaveSize 1
        query.submissions(since = null, verdict = null) shouldHaveSize 1
    }

    @Test
    fun `the correction wins, so the reader sees the attached code and not the pending state`() {
        val pending = aSubmissionRecord(codePending = true, codePath = null)
        val query = aRecordRepository(root)
            .containing(pending, pending.copy(codePending = false, codePath = "problems/120804/attempts/001.java"))
            .query()

        query.history().single().isCodeAttached() shouldBe true
    }

    /**
     * The failure that would be least visible: totals stay plausible while every attached
     * submission is counted twice, so a pass rate looks right and is not.
     */
    @Test
    fun `a correction does not double-count in the tally`() {
        val passed = aSubmissionRecord(verdict = Verdict.PASS, codePending = true)
        val failed = aSubmissionRecord(captureKey = CaptureKey("aaaabbbbccccdddd"), verdict = Verdict.WRONG)
        val query = aRecordRepository(root)
            .containing(passed, passed.copy(codePending = false), failed)
            .query()

        query.tally(TallyGroup.VERDICT).sumOf { it.count } shouldBe 2
    }

    @Test
    fun `a problem lists a corrected attempt once`() {
        val pending = aSubmissionRecord(lessonId = 120804, codePending = true)
        val query = aRecordRepository(root)
            .containing(pending, pending.copy(codePending = false))
            .query()

        query.problem(120804).submissions shouldHaveSize 1
    }

    /** #343 through the real store: identical SQL submits are counted, not folded. */
    @Test
    fun `byte-identical submits at different times are each a submission`() {
        val key = CaptureKey("dddd000000000001")
        fun submit(attempt: Int, at: String) = aSubmissionRecord(
            lessonId = 131537,
            attempt = attempt,
            ts = OffsetDateTime.parse(at),
            captureKey = key,
        )
        val query = aRecordRepository(root).containing(
            submit(1, "2026-10-03T15:25:45+09:00"),
            submit(2, "2026-10-03T15:53:58+09:00"),
            submit(3, "2026-10-03T15:55:57+09:00"),
        ).query()

        query.problem(131537).submissions.map { it.attempt } shouldContainExactly listOf(3, 2, 1)
        query.tally(TallyGroup.PROBLEM).single().count shouldBe 3
    }

    // lastRecordOf — what the badge asks on every heartbeat (#156) -----------------------------

    @Test
    fun `the newest grading recorded for a lesson is the one reported`() {
        val older = aSubmissionRecord(lessonId = 120802, ts = OffsetDateTime.parse("2026-08-11T13:01:00+09:00"))
        val newer = aSubmissionRecord(
            lessonId = 120802,
            ts = OffsetDateTime.parse("2026-08-11T13:24:00+09:00"),
            verdict = Verdict.PASS,
        )
        val other = aSubmissionRecord(lessonId = 181946, ts = OffsetDateTime.parse("2026-08-11T14:00:00+09:00"))

        val query = aRecordRepository(root).containing(older, newer, other).query()

        query.lastRecordOf(120802)?.ts shouldBe newer.ts
    }

    /**
     * Absent, not a placeholder. The badge reads this as "nothing recorded here yet", which is
     * a different thing from "recorded and unclassified" — and telling those two apart is the
     * whole reason the field exists.
     */
    @Test
    fun `a lesson with nothing recorded reports nothing`() {
        val query = aRecordRepository(root).containing(aSubmissionRecord(lessonId = 120804)).query()

        query.lastRecordOf(999999).shouldBeNull()
    }

    // repairSteps and codedProblem — spec 2026-10-07 §4.3 -----------------------------------------

    /** Spec §6, 4.3 acceptance in miniature: a failure, the run that corrected it, and the change. */
    @Test
    fun `a failed run and the run that followed it are a repair step with the diff of their kept code`() {
        val failed = aRun(at = "2026-10-07T10:51:49+09:00")
        val passed = aRun(at = "2026-10-07T10:51:51+09:00", verdict = Verdict.PASS)
        val query = aRecordRepository(root).containing(failed, passed)
            .withRunCode(failed, "select a").withRunCode(passed, "select b").query()

        val labelled = query.allRepairSteps().single()

        labelled.step.from.record.recordId() shouldBe failed.recordId()
        labelled.step.diff.shouldNotBeNull() shouldContain "+select b"
        labelled.problem.title shouldBe "두 수의 곱 구하기"
    }

    @Test
    fun `a run recorded before code was kept is a step that says its code is unknown`() {
        val failed = aRun(at = "2026-10-06T10:00:00+09:00")
        val passed = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.PASS)
        val query = aRecordRepository(root).containing(failed, passed).withRunCode(passed, "b").query()

        query.allRepairSteps().single().step.noDiff shouldBe NoDiff.FROM_CODE_UNKNOWN
    }

    @Test
    fun `a submit's code is read from its attempt file`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:01:00+09:00")
        val query = aRecordRepository(root).containing(run, submit)
            .withSubmitCode(submit, "fixed\n").withRunCode(run, "broken").query()

        query.allRepairSteps().single().step.diff.shouldNotBeNull() shouldContain "+fixed"
    }

    /**
     * Production-shaped: a run's record carries a codePath too — the Solution file every later grading
     * of the problem overwrites, never the run's own code, which is only in runs.jsonl. Reading that
     * path would hand every run the code of the last one, and the step would vanish as identical code.
     */
    @Test
    fun `a run's own code is not read from the solution file its record points at`() {
        val solution = "problems/120804-두-수의-곱-구하기/Solution.java"
        val first = aRun(at = "2026-10-07T10:00:00+09:00").copy(codePath = solution)
        val second = aRun(at = "2026-10-07T10:00:05+09:00").copy(codePath = solution)
        val repository = aRecordRepository(root).containing(first, second)
            .withRunCode(first, "select a").withRunCode(second, "select b")
        Files.createDirectories(root.resolve(solution).parent)
        Files.writeString(root.resolve(solution), "select b")

        repository.query().allRepairSteps().single().step.diff.shouldNotBeNull() shouldContain "-select a"
    }

    /** The code is attached after the record is durable (design §5.2); until then there is none to read. */
    @Test
    fun `a submit whose code is still pending has no code`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val pending = aSubmit(at = "2026-10-07T10:01:00+09:00").copy(codePending = true, codePath = null)
        val query = aRecordRepository(root).containing(run, pending).withRunCode(run, "broken").query()

        query.allRepairSteps().single().step.noDiff shouldBe NoDiff.TO_CODE_UNKNOWN
    }

    @Test
    fun `code attached after the next grading was recorded is marked late`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:02+09:00", verdict = Verdict.PASS)
        val query = aRecordRepository(root).containing(first, second)
            .withRunCode(first, "b", attachedAt = second.ts.plusSeconds(1))
            .withRunCode(second, "b").query()

        query.allRepairSteps().single().step.from.late shouldBe true
    }

    /** A run and the submit after it can land in the same second; the log's order says which came first. */
    @Test
    fun `gradings that share a timestamp are paired in the order the log was written`() {
        val same = "2026-10-07T10:00:00+09:00"
        val run = aRun(at = same)
        val submit = aSubmit(at = same)
        val query = aRecordRepository(root).containing(run, submit)
            .withRunCode(run, "a").withSubmitCode(submit, "b").query()

        query.allRepairSteps().single().step.from.record.recordId() shouldBe run.recordId()
    }

    @Test
    fun `narrows to one lesson before reading any code`() {
        val repository = aRecordRepository(root).containing(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2),
        )
        val codes = AskedCodes(FileGradingCodes(RecordLayout(root)))

        val page = repository.query(codes = codes).repairSteps(aRepairStepFilter(), lessonId = 2)

        page.steps.map { it.problem.lessonId }.shouldContainExactly(2L)
        codes.askedLessons.shouldContainExactly(2L)
    }

    /**
     * The filter arrives already built, so an argument it refuses was refused before the log or any
     * code was read: there is no query to run with one (`RepairStepFilterTest` pins what it refuses).
     * What the query owes the filter is to apply it, with each field to the thing it names.
     */
    @Test
    fun `applies the filter's language and part, each to the field it names`() {
        val query = aRecordRepository(root).containing(
            *pairOf(lessonId = 1, part = "SELECT", language = "java"),
            *pairOf(lessonId = 2, part = "JOIN", language = "java"),
            *pairOf(lessonId = 3, part = "SELECT", language = "kotlin"),
        ).query()

        query.repairSteps(aRepairStepFilter(language = "java", part = "SELECT"), lessonId = null)
            .steps.map { it.problem.lessonId }.shouldContainExactly(1L)
    }

    /**
     * D8, in the assembly: pairing runs over the whole history and `since` is applied after. Lesson 2's
     * pair straddles `since` — its failure is before it, its correction after — so its step exists only
     * if the failure was still there to be paired when `since` was applied.
     */
    @Test
    fun `applies the filter's since after pairing, and its limit keeps the newest corrections`() {
        val query = aRecordRepository(root).containing(
            *pairOf(lessonId = 1, part = "SELECT", language = "java"),
            *pairOf(lessonId = 2, part = "SELECT", language = "java"),
            *pairOf(lessonId = 3, part = "SELECT", language = "java"),
        ).query()
        val since = Since.Instant(OffsetDateTime.parse("2026-10-07T10:02:05+09:00"))

        val all = query.repairSteps(aRepairStepFilter(since = since), lessonId = null)
        val newest = query.repairSteps(aRepairStepFilter(since = since, limit = 1), lessonId = null)

        all.steps.map { it.problem.lessonId }.shouldContainExactly(3L, 2L)
        newest.steps.map { it.problem.lessonId }.shouldContainExactly(3L)
        newest.total shouldBe 2
        newest.isTruncated() shouldBe true
    }

    @Test
    fun `a problem graded in two languages assembles both languages' steps`() {
        val query = aRecordRepository(root).containing(
            aRun(at = "2026-10-07T10:00:00+09:00", language = "java"),
            aRun(at = "2026-10-07T10:00:10+09:00", language = "kotlin"),
            aRun(at = "2026-10-07T10:00:20+09:00", language = "java"),
            aRun(at = "2026-10-07T10:00:30+09:00", language = "kotlin"),
        ).query()

        query.allRepairSteps().map { it.step.to.record.language }.shouldContainExactly("kotlin", "java")
    }

    /**
     * The label is the one `get_problem` shows: each field from the newest record that carries it,
     * and of two records that share a timestamp the one written later.
     */
    @Test
    fun `a step's label is what get_problem shows for the problem, timestamp ties included`() {
        val same = "2026-10-07T10:00:00+09:00"
        val query = aRecordRepository(root).containing(
            aRun(at = same).copy(title = "first written"),
            aRun(at = same).copy(title = "second written"),
            aRun(at = "2026-10-07T10:00:05+09:00").copy(title = "", part = null, level = null),
        ).query()

        query.allRepairSteps().map { it.problem }.distinct() shouldBe
            listOf(ProblemLabel(lessonId = 120804, title = "second written", level = 0, part = "코딩테스트 입문"))
        query.problem(120804).title shouldBe "second written"
    }

    @Test
    fun `one problem's coded timeline carries each grading's code and the transition into it`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:05+09:00", verdict = Verdict.PASS)
        val query = aRecordRepository(root).containing(first, second)
            .withRunCode(first, "a").withRunCode(second, "b").query()

        val coded = query.codedProblem(query.problem(120804).submissions)

        coded.gradingOf(first)?.code?.text shouldBe "a"
        coded.transitionInto(second).shouldNotBeNull().diff.shouldNotBeNull() shouldContain "+b"
        coded.transitionInto(first).shouldBeNull()
        coded.gradingOf(aRun(at = "2026-10-07T11:00:00+09:00")).shouldBeNull()
    }

    @Test
    fun `a problem with nothing recorded has an empty coded timeline`() {
        val coded = aRecordRepository(root).query().codedProblem(emptyList())

        coded.gradings.shouldBeEmpty()
        coded.transitions.shouldBeEmpty()
    }

    @Test
    fun `the records of two problems are not one problem's timeline`() {
        val query = aRecordRepository(root).containing(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2),
        ).query()

        shouldThrow<IllegalArgumentException> { query.codedProblem(query.history()) }
    }

    /** The default port: a deployment without kept code still answers, with every code unknown. */
    @Test
    fun `a query with no code store answers every step without a diff`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:01:00+09:00")
        val store = aRecordRepository(root).containing(run, submit).store()
        val query = RecordQuery(store, anEmptyCatalog(), Clock.systemUTC(), raw())

        query.allRepairSteps().single().step.noDiff shouldBe NoDiff.CODE_UNKNOWN
    }

    private fun RecordQuery.allRepairSteps(): List<LabelledStep> =
        repairSteps(aRepairStepFilter(), lessonId = null).steps

    private fun raw() = FileRawSessionLog.under(root, Clock.systemUTC(), aStateDirectory(root))

    // Two failed runs of one problem, ten seconds apart, in the minute its lesson id names.
    private fun pairOf(lessonId: Long, part: String, language: String): Array<SubmissionRecord> = arrayOf(
        aRun(at = "2026-10-07T10:0$lessonId:00+09:00", lessonId = lessonId, language = language).copy(part = part),
        aRun(at = "2026-10-07T10:0$lessonId:10+09:00", lessonId = lessonId, language = language).copy(part = part),
    )

    /** Remembers which problems' run code was asked for; everything else is the real adapter's. */
    private class AskedCodes(private val real: GradingCodes) : GradingCodes by real {
        val askedLessons = mutableListOf<Long>()

        override fun runs(lessonId: Long, title: String?): Map<String, KeptCode> {
            askedLessons += lessonId
            return real.runs(lessonId, title)
        }
    }
}
