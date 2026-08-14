package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `problems/README.md` — the answer to "what have you solved" in the one form a browser renders
 * (#292).
 *
 * Everything asserted here is about being **legible outside Obsidian**: relative markdown links
 * rather than wikilinks (#293), a table GitHub draws, and counts that never become a verdict.
 */
class ProblemIndexTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `it lands where GitHub renders a directory listing`() {
        write(listOf(aSubmissionRecord())) shouldBe root.resolve("problems/README.md")
    }

    @Test
    fun `one row per problem, however many records it has`() {
        val text = render(
            listOf(
                aSubmissionRecord(lessonId = 1, title = "first"),
                aSubmissionRecord(lessonId = 1, title = "first", action = GradingAction.RUN),
                aSubmissionRecord(lessonId = 2, title = "second"),
            ),
        )

        rowsOf(text).size shouldBe 2
    }

    /**
     * A relative markdown link into the directory beside it. A wikilink would be literal text on
     * github.com and in IntelliJ, which is the whole reason this file exists (#293).
     */
    @Test
    fun `each row links to the problem's own page, relatively`() {
        render(listOf(aSubmissionRecord(lessonId = 120804, title = "두 수의 곱 구하기"))) shouldContain
            "[두 수의 곱 구하기](120804-두-수의-곱-구하기/README.md)"
    }

    @Test
    fun `it carries no wikilink at all`() {
        render(listOf(aSubmissionRecord())) shouldNotContain "[["
    }

    /**
     * A blank title is how "the catalog has never seen this problem" is spelled in the JSONL —
     * `SettledCapture.toRecord` writes `problem?.title.orEmpty()`. The row still has to link
     * somewhere, and the directory is named for the lesson id alone in exactly that case, so the
     * id becomes the link text too rather than the row rendering as `[](...)` (#309).
     */
    @Test
    fun `a problem the catalog never named is linked by its lesson id`() {
        render(listOf(aSubmissionRecord(lessonId = 120804, title = ""))) shouldContain
            "[120804](120804/README.md)"
    }

    /** Newest first is a fact about when things happened, not a ranking of them. */
    @Test
    fun `the newest problem is first`() {
        val text = render(
            listOf(
                aSubmissionRecord(lessonId = 1, title = "older", ts = at("2026-08-01T10:00:00Z")),
                aSubmissionRecord(lessonId = 2, title = "newer", ts = at("2026-08-09T10:00:00Z")),
            ),
        )

        rowsOf(text).first() shouldContain "newer"
    }

    /** Counts, and nothing that names a weakness (decisions/2026-08-12-the-server-counts-and-names-nothing). */
    @Test
    fun `it states how many problems and how many passed`() {
        val text = render(
            listOf(
                aSubmissionRecord(lessonId = 1, verdict = Verdict.PASS),
                aSubmissionRecord(lessonId = 2, verdict = Verdict.WRONG),
            ),
        )

        text shouldContain "2 problems recorded, 1 passed."
    }

    /**
     * No records, no file. An index of an empty directory helps nobody, and writing one would
     * break the property `StartupReconciliation` states out loud — a boot that had nothing to
     * recover does nothing, rather than manufacturing a commit to announce it.
     */
    @Test
    fun `an empty history writes no file at all`() {
        ProblemIndex(RecordLayout(root)).write(emptyList()).shouldBeNull()

        Files.exists(root.resolve("problems/README.md")) shouldBe false
    }

    /**
     * A run is not an attempt (design §5.1), so the submit count is submits — and a problem with
     * only runs still earns a row, because opening and running it is a fact about the history.
     */
    @Test
    fun `runs are listed but not counted as submits`() {
        val text = render(
            listOf(
                aSubmissionRecord(lessonId = 1, title = "runs only", action = GradingAction.RUN, verdict = null),
                aSubmissionRecord(lessonId = 1, title = "runs only", action = GradingAction.RUN, verdict = null),
            ),
        )

        rowsOf(text).size shouldBe 1
        rowsOf(text).first() shouldContain "| 0 |"
    }

    /** Absent stays absent, and reads as absent rather than as a table with a hole in it. */
    @Test
    fun `a field that was never recorded shows a dash`() {
        val text = render(listOf(aSubmissionRecord(level = null, kind = null)))

        rowsOf(text).first() shouldContain "| — |"
    }

    @Test
    fun `it reports the kind the channel gave, when there is one`() {
        val text = render(listOf(aSubmissionRecord(kind = ProblemKind.DATABASE, language = "mysql")))

        rowsOf(text).first() shouldContain "| database |"
    }

    private fun at(instant: String): OffsetDateTime = Instant.parse(instant).atOffset(ZoneOffset.ofHours(9))

    private fun rowsOf(text: String): List<String> =
        text.lines().filter { it.startsWith("| ") && !it.startsWith("| Problem") }

    private fun write(records: List<SubmissionRecord>): Path =
        checkNotNull(ProblemIndex(RecordLayout(root)).write(records))

    private fun render(records: List<SubmissionRecord>): String = Files.readString(write(records))
}
