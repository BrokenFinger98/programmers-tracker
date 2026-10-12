package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.unwritableWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * The records repository's front page, `README.md`, is the index (#414): what has been solved, where github.com opens.
 *
 * Two halves, and every test here is about one of them. **What it says** — a short header, then the counts and the
 * table `problems/README.md` has always carried, linked from the root. **Whose it is** — the server's while it is
 * exactly the page the server wrote, the owner's the moment one character differs. The second half is what makes
 * a regenerated index safe to write at every grading: an edit is never overwritten, and the server's own page,
 * however old, is never taken for one.
 */
class RootIndexTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private val readme: Path get() = root.resolve("README.md")

    // What it says ------------------------------------------------------------------------------------

    @Test
    fun `it lands at the root, where github com opens the repository`() {
        index().write(listOf(aSubmissionRecord())) shouldBe readme
    }

    /** What the repository is, and where the guide is in both languages: the only prose the page carries. */
    @Test
    fun `its header says what the repository is and links the guide in both languages`() {
        val text = render(listOf(aSubmissionRecord()))

        text shouldContain "# Programmers solving records"
        text shouldContain "[English](GUIDE.md)"
        text shouldContain "[한국어](GUIDE.ko.md)"
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

    /** From the root, a problem's page is one directory further down than from `problems/README.md`. */
    @Test
    fun `each row links to the problem's own page, from the root`() {
        render(listOf(aSubmissionRecord(lessonId = 120804, title = "두 수의 곱 구하기"))) shouldContain
            "[두 수의 곱 구하기](problems/120804-두-수의-곱-구하기/README.md)"
    }

    /**
     * Every relative link on the page names a file that is there, read from the root: each problem's page as its
     * writer wrote it, and the guide in both languages as the seeds wrote it. A link that resolves to nothing is the
     * defect #233 shipped once, when links and paths were spelled in two places.
     */
    @Test
    fun `every link on the page resolves from the root`() {
        val records = listOf(aSubmissionRecord(lessonId = 120804), aSubmissionRecord(lessonId = 1, title = ""))
        VaultDashboard(root, SeedLedger(root, aStateDirectory(root))).ensure()
        records.forEach { ProblemReadme(RecordLayout(root)).write(listOf(it)) }

        val links = LINK.findAll(render(records)).map { it.groupValues[1] }.filterNot { it.startsWith("https://") }

        links.toList() shouldHaveSize 4
        links.filterNot { Files.isRegularFile(root.resolve(it), NOFOLLOW_LINKS) }.toList().shouldBeEmpty()
    }

    /** A problem Programmers renamed is linked at the directory it has, not the one today's title would make (#233). */
    @Test
    fun `a renamed problem is linked at the directory it already has`() {
        Files.createDirectories(root.resolve("problems/120804-the-old-title"))

        render(listOf(aSubmissionRecord(lessonId = 120804, title = "the new title"))) shouldContain
            "[the new title](problems/120804-the-old-title/README.md)"
    }

    /** The table is the one `problems/README.md` carries, row for row; only where its links start differs. */
    @Test
    fun `its table is the problems index's, with links that start at the root`() {
        val records = listOf(
            aSubmissionRecord(lessonId = 1, title = "first", verdict = Verdict.WRONG),
            aSubmissionRecord(lessonId = 2, title = "second"),
        )
        val problems = Files.readString(checkNotNull(ProblemIndex(RecordLayout(root)).write(records)))

        rowsOf(render(records)) shouldBe rowsOf(problems).map { it.replaceFirst("](", "](problems/") }
    }

    /**
     * A fresh repository has a front page before it has a record: what the repository is, where the guide is, and a
     * count of none. A table with no rows would read as a defect, so there is none.
     */
    @Test
    fun `an empty history still gets a front page, with its counts and no table`() {
        val text = render(emptyList())

        text shouldContain "# Programmers solving records"
        text shouldContain "0 problems recorded, 0 passed."
        text shouldNotContain "| Problem |"
    }

    @Test
    fun `it carries no wikilink at all`() {
        render(listOf(aSubmissionRecord())) shouldNotContain "[["
    }

    // Whose it is -------------------------------------------------------------------------------------

    /** The page it wrote is its own however much the records moved since, which is every grading. */
    @Test
    fun `the page it wrote is rewritten when the records change`() {
        val index = index()
        index.write(listOf(aSubmissionRecord(lessonId = 1, title = "first")))

        index.write(listOf(aSubmissionRecord(lessonId = 1, title = "first"), aSubmissionRecord(lessonId = 2)))

        Files.readString(readme) shouldContain "2 problems recorded"
    }

    /** A server restarted is a new instance with nothing remembered: the page has to say whose it is by itself. */
    @Test
    fun `a page an earlier server wrote is still its own`() {
        RootIndex(RecordLayout(root)).write(listOf(aSubmissionRecord(lessonId = 1)))

        render(listOf(aSubmissionRecord(lessonId = 1), aSubmissionRecord(lessonId = 2))) shouldContain
            "2 problems recorded"
    }

    /** A front page of the owner's own is theirs: never overwritten, and said once however often the index is due. */
    @Test
    fun `a page of the owner's own is never overwritten, and that is said once`() {
        Files.writeString(readme, "# my records\n")
        val index = index()

        val heard = warningsWhile(RootIndex::class) {
            repeat(3) { index.write(listOf(aSubmissionRecord())).shouldBeNull() }
        }

        Files.readString(readme) shouldBe "# my records\n"
        heard.size shouldBe 1
        heard.single() shouldContain readme.toString()
        heard.single() shouldContain "problems/README.md"
    }

    /** One character is an edit, and an edit is the owner's (#300's rule): the page is not written again. */
    @Test
    fun `the page it wrote, changed by one character, is the owner's`() {
        index().write(listOf(aSubmissionRecord(lessonId = 1)))
        val edited = Files.readString(readme).replace("1 problems recorded", "1 problem recorded")
        Files.writeString(readme, edited)

        index().write(listOf(aSubmissionRecord(lessonId = 1), aSubmissionRecord(lessonId = 2))).shouldBeNull()

        Files.readString(readme) shouldBe edited
    }

    /**
     * An addition is an edit too, wherever it is made: above the page, inside it, below it. So is a change to the
     * words of the mark itself, which the digest does not cover: the whole first line is compared, not its digest.
     */
    @Test
    fun `a line added anywhere, or the mark's own words changed, makes the page the owner's`() {
        index().write(listOf(aSubmissionRecord()))
        val written = Files.readString(readme)
        val edits = listOf(
            "mine\n$written",
            written.replaceFirst("\n", "\nmine\n"),
            "${written}mine\n",
            written.replaceFirst("programmers-tracker", "me"),
        )

        edits.forEach { edited ->
            Files.writeString(readme, edited)
            index().write(listOf(aSubmissionRecord(), aSubmissionRecord(lessonId = 2))).shouldBeNull()
            Files.readString(readme) shouldBe edited
        }
    }

    /** The server's mark copied onto a page of the owner's does not make it the server's: it vouches for one page. */
    @Test
    fun `the mark of one page does not vouch for another`() {
        index().write(listOf(aSubmissionRecord()))
        val mark = Files.readString(readme).lines().first()
        Files.writeString(readme, "$mark\n# my records\n")

        index().write(listOf(aSubmissionRecord())).shouldBeNull()

        Files.readString(readme) shouldBe "$mark\n# my records\n"
    }

    /** Git with `core.autocrlf` checks a page out with CRLF endings. That is the checkout's doing, not an edit. */
    @Test
    fun `a checkout that rewrote its line endings is still the page it wrote`() {
        index().write(listOf(aSubmissionRecord(lessonId = 1)))
        Files.writeString(readme, Files.readString(readme).replace("\n", "\r\n"))

        render(listOf(aSubmissionRecord(lessonId = 1), aSubmissionRecord(lessonId = 2))) shouldContain
            "2 problems recorded"
    }

    /** The mark is a comment no renderer shows: github.com and Obsidian's reading view start the page at its title. */
    @Test
    fun `the mark is one comment line above the title`() {
        val lines = render(listOf(aSubmissionRecord())).lines()

        lines.first() shouldStartWith "<!--"
        lines.first() shouldContain "-->"
        lines[1] shouldBe "# Programmers solving records"
    }

    // Written over nothing it does not own, and never through a link (#361) --------------------------------

    /** A link is something somebody made, which the front page, like a seed, leaves alone: never written through. */
    @Test
    fun `a front page that is a link is left alone, and the file it leads to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside)
        aLink(readme, elsewhere)

        val heard = warningsWhile(RootIndex::class) { index().write(listOf(aSubmissionRecord())).shouldBeNull() }

        Files.readString(elsewhere) shouldBe NOT_OURS
        Files.isSymbolicLink(readme) shouldBe true
        heard.single() shouldContain readme.toString()
    }

    @Test
    fun `a dangling link is left alone, and nothing is created where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-front-page.md")
        aLink(readme, nowhere)

        index().write(listOf(aSubmissionRecord())).shouldBeNull()

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.isSymbolicLink(readme) shouldBe true
    }

    /** Written at every attachment and boot, so a page the file system will not take is skipped and said: no throw. */
    @Test
    fun `a front page the file system will not take is skipped, and said once with its path`() {
        assumeTrue(keepsPosixPermissions(root), "this test closes a directory to writes")
        val index = index()

        val heard = warningsWhile(RecordWrites::class) {
            unwritableWhile(root) {
                assumeTrue(!Files.isWritable(root), "a superuser writes anyway")
                repeat(2) { index.write(listOf(aSubmissionRecord())).shouldBeNull() }
            }
        }

        heard.single() shouldContain readme.toString()
    }

    private fun rowsOf(text: String): List<String> =
        text.lines().filter { it.startsWith("| ") && !it.startsWith("| Problem") }

    private fun index() = RootIndex(RecordLayout(root))

    private fun render(records: List<SubmissionRecord>): String = Files.readString(checkNotNull(index().write(records)))

    private companion object {
        /** A markdown link's target: what a renderer resolves against the page's own directory. */
        val LINK = Regex("""\]\(([^)]+)\)""")
    }
}
