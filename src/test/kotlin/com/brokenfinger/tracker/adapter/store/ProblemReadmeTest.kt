package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.TestcaseSummary
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProblemReadmeTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `the README lands in the problem directory of its lesson`() {
        val file = write(listOf(aSubmissionRecord()))

        file shouldBe RecordLayout(root).problemDirectory(120804, "두 수의 곱 구하기").resolve("README.md")
        Files.isRegularFile(file) shouldBe true
    }

    @Test
    fun `the frontmatter carries what the records actually know`() {
        val text = render(listOf(aSubmissionRecord()))

        text shouldContain "lessonId: 120804"
        text shouldContain """title: "두 수의 곱 구하기""""
        text shouldContain "level: 0"
        text shouldContain """part: "코딩테스트 입문""""
        text shouldContain "acceptanceRate: 91"
        text shouldContain """tags: ["구현"]"""
        text shouldContain """language: "java""""
    }

    /**
     * The language beside `verdict: PASS` has to be the language that passed. It was taken from
     * the newest record of any kind, so lesson 120802 — passed in java, then *run* once in
     * python3 — published `language: "python3"` next to `verdict: PASS` for a python3 grading
     * that never produced a verdict (#248). A run is not an attempt (design §5.1), and this was
     * the fourth place that needed telling.
     */
    @Test
    fun `the language is the one that was submitted, not the one last run`() {
        val text = render(
            listOf(
                aSubmissionRecord(action = GradingAction.SUBMIT, language = "java", verdict = Verdict.PASS),
                aSubmissionRecord(action = GradingAction.RUN, language = "python3", verdict = null),
            ),
        )

        text shouldContain """language: "java""""
        text shouldNotContain "python3"
    }

    /** Two submits, and the newest wins — a problem re-solved in another language moved on. */
    @Test
    fun `a problem re-solved in another language reports the newer one`() {
        val text = render(
            listOf(
                aSubmissionRecord(action = GradingAction.SUBMIT, language = "java"),
                aSubmissionRecord(action = GradingAction.SUBMIT, language = "kotlin"),
            ),
        )

        text shouldContain """language: "kotlin""""
    }

    /**
     * Nothing submitted, so there is no language to report — absent rather than borrowed from a
     * run, for the same reason an unrecorded title has no key. `runCount` still says the problem
     * was touched.
     */
    @Test
    fun `a problem only ever run reports no language`() {
        val text = render(listOf(aSubmissionRecord(action = GradingAction.RUN, language = "python3")))

        text shouldNotContain "language:"
        text shouldContain "runCount: 1"
    }

    @Test
    fun `the frontmatter carries the progress the records add up to`() {
        val records = listOf(aRun(elapsedSec = 100), aSubmit(attempt = 1), aRun(elapsedSec = 700))

        val text = render(records)

        text shouldContain "verdict: PASS"
        text shouldContain "attempts: 1"
        text shouldContain "runCount: 2"
        text shouldContain "elapsedSec: 700"
        text shouldContain "firstSeen: 2026-08-04"
        text shouldContain "lastSubmit: 2026-08-04"
    }

    // The state every record is in today: no catalog is wired, so `title` is empty on every
    // record and level, part and acceptanceRate are unset.
    @Test
    fun `a record with no catalog metadata omits those fields rather than inventing them`() {
        val text = render(listOf(withoutCatalog()))

        text shouldNotContain "title:"
        text shouldNotContain "level:"
        text shouldNotContain "part:"
        text shouldNotContain "acceptanceRate:"
        text shouldNotContain "tags:"
        text shouldContain "lessonId: 120804"
    }

    @Test
    fun `an unknown title leaves the lesson id standing as the heading, never a guessed name`() {
        val text = render(listOf(withoutCatalog()))

        text shouldContain "\n# 120804\n"
        text shouldContain "#programmers"
        text shouldNotContain "#Lv"
    }

    @Test
    fun `an unknown title leaves the directory named after the lesson id alone`() {
        val file = write(listOf(withoutCatalog()))

        file.parent.fileName.toString() shouldBe "120804"
    }

    @Test
    fun `a Korean title and Korean tags render verbatim`() {
        val text = render(listOf(aSubmissionRecord()))

        text shouldContain "\n# 두 수의 곱 구하기\n"
        text shouldContain "#Lv0"
        text shouldContain "#구현"
    }

    @Test
    fun `every submit gets a row and runs are counted instead`() {
        val text = render(listOf(aSubmit(attempt = 1), aRun(), aSubmit(attempt = 2)))

        rowsOf(text).size shouldBe 2
        text shouldContain "| 1 |"
        text shouldContain "| 2 |"
        text shouldContain "runCount: 1"
    }

    @Test
    fun `a row states the verdict, the testcases, the elapsed time and whether a diff exists`() {
        val text = render(listOf(aSubmissionRecord(attempt = 2, elapsedSec = 847)))

        rowsOf(text).single() shouldBe "| 2 | 14:23 | PASS | 1/1 | 14m07s | yes |"
    }

    @Test
    fun `a submit that was never judged shows its outcome instead of borrowing a verdict`() {
        val record = aSubmissionRecord(outcome = Outcome.INCOMPLETE, verdict = null, diffFromPrev = null)

        rowsOf(render(listOf(record))) shouldBe listOf("| 2 | 14:23 | INCOMPLETE | 1/1 | 14m07s | no |")
    }

    @Test
    fun `a partially observed testcase set is marked rather than passed off as complete`() {
        val testcases = listOf(aTestcaseResult(), aTestcaseResult(id = 2, passed = false))
        val record = aSubmissionRecord(
            verdict = Verdict.WRONG,
            testcases = testcases,
            tcSummary = TestcaseSummary.of(testcases, complete = false),
        )

        rowsOf(render(listOf(record))).single() shouldContain "| 1/2 (partial) |"
    }

    @Test
    fun `an hour of work reads as hours rather than as a long minute count`() {
        rowsOf(render(listOf(aSubmissionRecord(elapsedSec = 3_723)))).single() shouldContain "| 1h02m03s |"
    }

    @Test
    fun `only runs so far means no attempt row and no lastSubmit`() {
        val text = render(listOf(aRun()))

        rowsOf(text).size shouldBe 0
        text shouldContain "attempts: 0"
        text shouldNotContain "lastSubmit:"
        text shouldContain "No submission yet."
    }

    @Test
    fun `a hand-edited README is replaced whole, with no leftovers and no merge markers`() {
        val file = write(listOf(aSubmissionRecord()))
        Files.writeString(file, "# my own notes\n\nkeep this please\n")

        val rewritten = Files.readString(write(listOf(aSubmissionRecord())))

        rewritten shouldNotContain "keep this please"
        rewritten shouldBe Files.readString(write(listOf(aSubmissionRecord())))
    }

    @Test
    fun `notes written by a human are never touched`() {
        val notes = write(listOf(aSubmissionRecord())).resolveSibling("notes.md")
        Files.writeString(notes, "why I got this wrong: overflow\n")

        write(listOf(aSubmissionRecord()))

        Files.readString(notes) shouldBe "why I got this wrong: overflow\n"
    }

    @Test
    fun `regenerating from the same records rewrites the same bytes`() {
        val records = listOf(aRun(), aSubmit(attempt = 1), aSubmissionRecord())

        val first = Files.readAllBytes(write(records))
        val second = Files.readAllBytes(write(records))

        second.toList() shouldBe first.toList()
    }

    @Test
    fun `a README of no records is refused — there is nothing to say`() {
        shouldThrow<IllegalArgumentException> { write(emptyList()) }
    }

    @Test
    fun `records of two different problems are refused rather than blended into one page`() {
        val other = aSubmissionRecord(lessonId = 131528, title = "인기있는 아이스크림")

        shouldThrow<IllegalArgumentException> { write(listOf(aSubmissionRecord(), other)) }
    }

    /**
     * An embed rather than a copy (#275): this file is rewritten on every grading and
     * `statement.md` is written once, so pasting the statement in would mean re-fetching it
     * every time to keep it. Obsidian expands the embed, so the reader still sees one page.
     */
    @Test
    fun `it embeds the statement when one has been captured`() {
        val record = aSubmissionRecord()
        val statement = RecordLayout(root).statementFile(record.lessonId, record.title)
        Files.createDirectories(statement.parent)
        Files.writeString(statement, "the problem\n")

        render(listOf(record)) shouldContain "\n![[statement]]\n"
    }

    /**
     * A wikilink to a note that is not there renders as a broken link and puts a phantom node on
     * the graph — so a problem whose page carried no statement gets no link at all.
     */
    @Test
    fun `it links nothing when no statement was captured`() {
        render(listOf(aSubmissionRecord())) shouldNotContain "![["
    }

    private fun write(records: List<SubmissionRecord>): Path = ProblemReadme(RecordLayout(root)).write(records)

    private fun render(records: List<SubmissionRecord>): String = Files.readString(write(records))

    private fun rowsOf(text: String): List<String> =
        text.lines().filter { it.startsWith("| ") && !it.startsWith("| # ") }

    private fun aRun(elapsedSec: Long = 100) =
        aSubmissionRecord(action = GradingAction.RUN, attempt = 0, elapsedSec = elapsedSec)

    private fun aSubmit(attempt: Int, elapsedSec: Long = 500) =
        aSubmissionRecord(action = GradingAction.SUBMIT, attempt = attempt, elapsedSec = elapsedSec)

    // Every record written today looks like this: no catalog is wired, so the title arrives empty.
    /**
     * The browser can show a cached 100.0 while the row says UNKNOWN (#74). The measured
     * reason rides in the row; an unmeasured one stays absent rather than guessed.
     */
    @Test
    fun `a cached-result unknown names its reason in the attempt row`() {
        val cached = aSubmissionRecord(
            outcome = Outcome.UNKNOWN,
            verdict = null,
            errorText = "같은 코드로 채점한 결과가 있습니다.",
        )

        val text = Files.readString(write(listOf(cached)))

        text shouldContain "UNKNOWN (cached result)"
    }

    @Test
    fun `an unexplained unknown stays bare in the attempt row`() {
        val odd = aSubmissionRecord(
            outcome = Outcome.UNKNOWN,
            verdict = null,
            errorText = "서버 점검 중입니다.",
        )

        val text = Files.readString(write(listOf(odd)))

        text shouldContain "| UNKNOWN |"
    }

    private fun withoutCatalog() =
        aSubmissionRecord(title = "", level = null, part = null, acceptanceRate = null, tags = emptyList())

    /**
     * Obsidian's graph draws links, not hashtags. The inline `#dp` drives the tag pane and
     * search and cannot carry a denominator; the wikilink is what puts an edge between a problem
     * and its tag note (#229). Both stay, because they serve different features.
     */
    @Test
    fun `the page links to each of its tag notes and keeps the hashtags`() {
        val text = render(listOf(aSubmissionRecord(tags = listOf("dp", "math"))))

        text shouldContain "[[tags/dp]]"
        text shouldContain "[[tags/math]]"
        text shouldContain "#dp"
    }

    /**
     * Algorithm or SQL, said rather than inferred (#256). Until now the only way to tell was to
     * read `language` or `part` and guess, and both break the day Programmers renames a part or
     * adds a database language — an inference standing in for a fact the server already had, since
     * it picks the channel by exactly this.
     */
    @Test
    fun `the page says which kind of problem it was`() {
        render(listOf(aSubmissionRecord(kind = ProblemKind.DATABASE))) shouldContain "kind: database"
        render(listOf(aSubmissionRecord(kind = ProblemKind.ALGORITHM))) shouldContain "kind: algorithm"
    }

    /** A record written before #256 has none, and an invented one would name a channel we never used. */
    @Test
    fun `a record that predates the field carries no kind`() {
        render(listOf(aSubmissionRecord(kind = null))) shouldNotContain "kind:"
    }

    /** A problem outside the shipped catalog carries no tags, and no link is invented for it. */
    @Test
    fun `a page for a problem with no tags links to none`() {
        val text = render(listOf(aSubmissionRecord(tags = emptyList())))

        text shouldNotContain "[[tags/"
    }

    /**
     * The link is a path, so it uses the note's file name. Writing the tag instead is how 43 of
     * the catalog's 83 tags shipped with links that resolved to nothing (#233) — every tag with
     * an underscore in it.
     */
    @Test
    fun `a tag the filesystem renames is linked by its file name`() {
        val text = render(listOf(aSubmissionRecord(tags = listOf("binary_search"))))

        text shouldContain "[[tags/binary-search]]"
        text shouldNotContain "[[tags/binary_search]]"
    }
}
