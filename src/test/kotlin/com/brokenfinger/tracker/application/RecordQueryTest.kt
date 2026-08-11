package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.Since
import com.brokenfinger.tracker.domain.calc.TallyGroup
import com.brokenfinger.tracker.support.fixtures.aPartialRecordLine
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTornRecordLine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
}
