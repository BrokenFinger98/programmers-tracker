package com.brokenfinger.tracker.adapter.store

import ch.qos.logback.classic.Level
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.madeFifo
import com.brokenfinger.tracker.support.fixtures.unwritableWhile
import com.brokenfinger.tracker.support.logging.loggedWhile
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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

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

    /**
     * A history read as empty — a log moved away, or every line unreadable — is no count to put on a page already
     * there: it is left as it is, as `problems/README.md` is (#414's review). Only a repository with no page gets the
     * zero-count one.
     */
    @Test
    fun `an empty history leaves a page already there as it is`() {
        val index = index()
        index.write(listOf(aSubmissionRecord(lessonId = 1), aSubmissionRecord(lessonId = 2)))
        val before = Files.readString(readme)

        index.write(emptyList()).shouldBeNull()

        Files.readString(readme) shouldBe before
        before shouldContain "2 problems recorded"
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

    /**
     * A front page of the owner's own is theirs: never overwritten, and said once however often the index is due. It
     * is their choice rather than a fault, so it is stated, not warned — the rule `StartupReconciliation` follows for a
     * repository with no remote — and the guide an older version seeded is named among what it can be, since a clone
     * with no ledger keeps that guide as the owner's (#414's review).
     */
    @Test
    fun `a page of the owner's own is never overwritten, and that is stated once, not warned`() {
        Files.writeString(readme, "# my records\n")
        val index = index()
        var stated = emptyList<String>()

        val warned = warningsWhile(RootIndex::class) {
            stated = loggedWhile(RootIndex::class, Level.INFO) {
                repeat(3) { index.write(listOf(aSubmissionRecord())).shouldBeNull() }
            }
        }

        Files.readString(readme) shouldBe "# my records\n"
        warned.shouldBeEmpty()
        stated.single() shouldContain readme.toString()
        stated.single() shouldContain "problems/README.md"
        stated.single() shouldContain "the guide an older version seeded"
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
     * An addition is an edit too, wherever it is made: above the seal, inside it — which splits it over two lines —
     * just below it, and at the end of the page.
     */
    @Test
    fun `a line added above, inside or below the seal makes the page the owner's`() {
        index().write(listOf(aSubmissionRecord()))
        val written = Files.readString(readme)
        val edits = listOf(
            "mine\n$written",
            written.replaceFirst(" sha256:", "\nmine sha256:"),
            written.replaceFirst("\n", "\nmine\n"),
            "${written}mine\n",
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

    /**
     * The seal's words are presentation: a release that rewords them must not orphan every page already deployed. A
     * page sealed under other words, its digest intact, is still the server's, and is rewritten under today's.
     */
    @Test
    fun `a page sealed under other words is the server's, and is rewritten with today's`() {
        index().write(listOf(aSubmissionRecord(lessonId = 1)))
        val body = Files.readString(readme).substringAfter('\n')
        Files.writeString(readme, "<!-- Sealed by an older programmers-tracker. sha256:${sha256Of(body)} -->\n$body")

        val text = render(listOf(aSubmissionRecord(lessonId = 1), aSubmissionRecord(lessonId = 2)))

        text shouldContain "2 problems recorded"
        text shouldBe PageSeal.sealed(text.substringAfter('\n'))
    }

    /**
     * The seal's framing is what every deployed page carries, so it is pinned to the byte: this version's first line
     * for a body whose digest was taken outside the code under test (`printf '# A known body\n' | shasum -a 256`).
     */
    @Test
    fun `the first line this version writes, for a known body`() {
        PageSeal.sealed("# A known body\n").lines().first() shouldBe
            "<!-- Written by programmers-tracker. Edit this page and it is yours: it is never written again, and " +
            "the index stays in problems/README.md. " +
            "sha256:7c02085cfb8b1dbf007d9c9ce0f68cac716b13969dcdb3d3c8aa8cdf7ffc72ed -->"
    }

    /** A digest is exactly 64 lowercase hex: one in capitals, one short or one long, is no seal at all. */
    @Test
    fun `a seal whose digest is not 64 lowercase hex is no seal`() {
        val body = "# A known body\n"
        val digest = sha256Of(body)

        listOf(digest.uppercase(), digest.drop(1), digest + "0").forEach { written ->
            PageSeal.isIntact("<!-- a seal sha256:$written -->\n$body".toByteArray()) shouldBe false
        }
        PageSeal.isIntact("<!-- a seal sha256:$digest -->\n$body".toByteArray()) shouldBe true
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
        heard.single() shouldContain "could not be read as a page"
    }

    /**
     * A FIFO there is refused by the read, which never opens one — a read would wait for a writer that never comes —
     * so what this pins is that the call returns at all, that nothing replaces the FIFO, and that it is warned once.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO at the front page is left alone, warned once, and not waited on`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a FIFO")
        assumeTrue(madeFifo(readme), "no mkfifo on this machine")
        val index = index()

        val heard = warningsWhile(RootIndex::class) {
            repeat(2) { index.write(listOf(aSubmissionRecord())).shouldBeNull() }
        }

        Files.isRegularFile(readme, NOFOLLOW_LINKS) shouldBe false
        heard.single() shouldContain readme.toString()
    }

    @Test
    fun `a directory at the front page is left alone, and warned once`() {
        Files.createDirectory(readme)
        val index = index()

        val heard = warningsWhile(RootIndex::class) {
            repeat(2) { index.write(listOf(aSubmissionRecord())).shouldBeNull() }
        }

        Files.isDirectory(readme, NOFOLLOW_LINKS) shouldBe true
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

    // Taken here rather than asked of the code under test, so a digest computed wrongly there cannot agree with itself.
    private fun sha256Of(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** A markdown link's target: what a renderer resolves against the page's own directory. */
        val LINK = Regex("""\]\(([^)]+)\)""")
    }
}
