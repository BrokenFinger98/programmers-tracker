package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.fixtures.unwritableWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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

    @TempDir
    lateinit var outside: Path

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

    // Written over a link, never through one (#361) ---------------------------------------------------

    @Test
    fun `an index that is a link is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val index = aLink(root.resolve("problems/README.md"), token)

        val heard = warningsWhile(RecordWrites::class) { index().write(listOf(aSubmissionRecord())) }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.isSymbolicLink(index) shouldBe false
        Files.readString(index) shouldContain "# Problems"
        heard.single() shouldContain index.toString()
    }

    /** Derived from the log at every attachment and boot, so a refused index is skipped and said, not thrown. */
    @Test
    fun `a problems directory that is a link gets no index, and nothing is written where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems"), outside)

        val heard = warningsWhile(RecordWrites::class) {
            index().write(listOf(aSubmissionRecord())).shouldBeNull()
        }

        namesIn(outside).shouldBeEmpty()
        heard.single() shouldContain "problems is a symbolic link"
    }

    @Test
    fun `an index that is a dangling link is replaced, and nothing is created where it pointed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-an-index.md")
        val index = aLink(root.resolve("problems/README.md"), nowhere)

        val heard = warningsWhile(RecordWrites::class) { index().write(listOf(aSubmissionRecord())) }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.readString(index) shouldContain "# Problems"
        heard.single() shouldContain index.toString()
    }

    // An index the file system will not take (#407's review) ------------------------------------------

    /**
     * Thrown, it took the tag notes after it down with it, at every attachment and boot. Written again at each, so it
     * is skipped as a refused one is, and said once with its path.
     */
    @Test
    fun `an index the file system will not take is skipped, and said once with its path`() {
        assumeTrue(keepsPosixPermissions(root), "this test closes a directory to writes")
        val problems = Files.createDirectories(root.resolve("problems"))
        val index = index()

        val heard = warningsWhile(RecordWrites::class) {
            unwritableWhile(problems) {
                assumeTrue(!Files.isWritable(problems), "a superuser writes anyway")
                repeat(2) { index.write(listOf(aSubmissionRecord())).shouldBeNull() }
            }
        }

        heard.single() shouldContain problems.resolve("README.md").toString()
    }

    private fun at(instant: String): OffsetDateTime = Instant.parse(instant).atOffset(ZoneOffset.ofHours(9))

    private fun rowsOf(text: String): List<String> =
        text.lines().filter { it.startsWith("| ") && !it.startsWith("| Problem") }

    private fun index() = ProblemIndex(RecordLayout(root))

    private fun write(records: List<SubmissionRecord>): Path = checkNotNull(index().write(records))

    private fun render(records: List<SubmissionRecord>): String = Files.readString(write(records))
}
