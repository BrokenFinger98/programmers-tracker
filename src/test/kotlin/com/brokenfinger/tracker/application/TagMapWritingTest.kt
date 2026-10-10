package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.support.fixtures.aCatalogEntry
import com.brokenfinger.tracker.support.fixtures.aCatalogOf
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.fixtures.unwritableWhile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Which notes each caller rewrites, which is the half a unit test of the calculator cannot see.
 *
 * The stub catalog carries two tags rather than the shipped 83, so these tests pin the behaviour
 * and not the number: the catalog is a snapshot that can be replaced, and a test that agreed
 * with today's count would fail on the day it is.
 */
class TagMapWritingTest {
    @TempDir
    lateinit var root: Path

    private val catalog = aCatalogOf(
        aCatalogEntry(id = 120804, tags = listOf("arithmetic")),
        aCatalogEntry(id = 120805, tags = listOf("dp")),
    )

    /** A record touches its own tags. The rest are unchanged, and rewriting them would be noise. */
    @Test
    fun `attaching code refreshes the tag notes of that problem only`() {
        val written = mutableListOf<TagCount>()

        attach(RecordingArtifacts(written), aSubmissionRecord(lessonId = 120804, tags = listOf("arithmetic")))

        written.map { it.tag } shouldContainExactly listOf("arithmetic")
    }

    /**
     * The record's own tags decide, not the catalog's whole set. A problem the catalog does not
     * describe carries none, so nothing is rewritten and no note is invented for it.
     */
    @Test
    fun `a record with no tags rewrites nothing`() {
        val written = mutableListOf<TagCount>()

        attach(RecordingArtifacts(written), aSubmissionRecord(lessonId = 999, tags = emptyList()))

        written.shouldContainExactly(emptyList())
    }

    /**
     * Startup writes every note, including the tag no record has ever touched — that note is the
     * isolated node the map exists for, and only a whole-map pass can produce it.
     */
    @Test
    fun `the startup refresh writes every catalogued tag, touched or not`() {
        val written = mutableListOf<TagCount>()

        attachment(RecordingArtifacts(written)).refreshVault()

        written.map { it.tag } shouldContainExactlyInAnyOrder listOf("arithmetic", "dp")
        written.single { it.tag == "dp" } shouldBe TagCount("dp", catalogTotal = 1, attempted = 0, solved = 0)
    }

    private fun attach(artifacts: DerivedArtifacts, record: SubmissionRecord) =
        runBlocking { attachment(artifacts).attach(record) }

    private fun attachment(artifacts: DerivedArtifacts) = CodeAttachment(
        fetcher = { _, _ -> CodeFetch.Fetched("class Solution {}") },
        store = store(),
        artifacts = artifacts,
        catalog = catalog,
        writerDispatcher = Dispatchers.Unconfined,
    )

    private fun store(): RecordStore = JsonlRecordStore.under(root)

    /**
     * The tag notes are only half the map: Obsidian draws edges from links, and a problem page
     * written by an earlier build has none. Without this, a vault upgraded into the tag map shows
     * 83 isolated nodes and no edges at all — the opposite of the point.
     *
     * Startup is where it heals, for the same reason the tag map refreshes there: it is the one
     * moment that can see every problem at once.
     */
    @Test
    fun `the startup refresh rewrites the problem pages too, so the links exist`() {
        val store = store()
        store.append(SubmissionRecordJson.encode(aSubmissionRecord(lessonId = 120804, tags = listOf("arithmetic"))))

        attachment(FileDerivedArtifacts(root, store, Clock.systemDefaultZone())).refreshVault()

        val page = RecordLayout(root).problemDirectory(120804, "두 수의 곱 구하기").resolve("README.md")
        Files.readString(page) shouldContain "[arithmetic](../../tags/arithmetic.md)"
    }

    /**
     * One page that could not be replaced — held open on Windows, a read-only mount — threw out of the loop, so every
     * page after it, the index and the tag map kept what they held until a later boot (#407). Each page is refreshed
     * on its own now: the one that fails is said once by the page and skipped, and the rest of the vault is written.
     */
    @Test
    fun `a page that cannot be replaced is skipped, and the rest of the vault is refreshed`() {
        assumeTrue(keepsPosixPermissions(root), "this test closes a directory to writes")
        val artifacts = refreshedOnceThenForgotten()
        val stuck = RecordLayout(root).problemDirectory(120804, TITLE)

        unwritableWhile(stuck) {
            assumeTrue(!Files.isWritable(stuck), "a superuser writes anyway")
            attachment(artifacts).refreshVault()
        }

        refreshedAfterTheStuckPage()
    }

    /** The case the issue measured: on Windows, a page another process holds open without sharing deletion. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a page held open by another process is skipped, and the rest of the vault is refreshed`() {
        val artifacts = refreshedOnceThenForgotten()
        val held = RecordLayout(root).problemDirectory(120804, TITLE).resolve("README.md")

        FileInputStream(held.toFile()).use { attachment(artifacts).refreshVault() }

        refreshedAfterTheStuckPage()
    }

    /**
     * Both problems recorded and the vault refreshed once, then everything the refresh writes after the first page
     * deleted, so a second refresh shows whether it got past that page. Shared by both tests, so what the Windows one
     * sets up runs on every platform. The files are listed one by one: a `Path` is an `Iterable<Path>`, so `list + path`
     * added the path's names, `Users` first on Windows, rather than the path (PR #411's CI).
     */
    private fun refreshedOnceThenForgotten(): DerivedArtifacts {
        val artifacts = FileDerivedArtifacts(root, twoProblemsRecorded(), Clock.systemDefaultZone())
        attachment(artifacts).refreshVault()
        val layout = RecordLayout(root)
        listOf(layout.problemDirectory(120805, TITLE).resolve("README.md"), layout.problemIndex(), layout.tagNote("dp"))
            .forEach(Files::delete)
        return artifacts
    }

    // The first problem's page is the one that fails, so everything the vault refresh writes comes after it.
    private fun twoProblemsRecorded(): RecordStore = store().apply {
        append(SubmissionRecordJson.encode(aSubmissionRecord(lessonId = 120804, tags = listOf("arithmetic"))))
        append(SubmissionRecordJson.encode(aSubmissionRecord(lessonId = 120805, tags = listOf("dp"))))
    }

    private fun refreshedAfterTheStuckPage() {
        val layout = RecordLayout(root)
        Files.readString(layout.problemDirectory(120805, TITLE).resolve("README.md")) shouldContain "lessonId: 120805"
        Files.exists(layout.problemIndex()) shouldBe true
        Files.exists(layout.tagNote("dp")) shouldBe true
    }

    /**
     * Records what it was asked to write and does the real work for everything else, so the
     * assertion is on the argument rather than on an interaction — a spy here would prove the
     * method was called and not what it was called with.
     */
    private inner class RecordingArtifacts(private val seen: MutableList<TagCount>) : DerivedArtifacts {
        private val delegate = FileDerivedArtifacts(root, store(), Clock.systemDefaultZone())

        override fun writeCode(record: SubmissionRecord, code: String) = delegate.writeCode(record, code)

        override fun writeRunner(record: SubmissionRecord, code: String) = delegate.writeRunner(record, code)

        override fun writeStatement(record: SubmissionRecord, markdown: String) = Unit

        override fun writeIndex(records: List<SubmissionRecord>) = Unit

        override fun writeReadme(records: List<SubmissionRecord>) = delegate.writeReadme(records)

        override fun writeTagNotes(counts: List<TagCount>) {
            seen += counts
        }
    }

    private companion object {
        /** The title every record here carries: what names a problem's directory. */
        const val TITLE = "두 수의 곱 구하기"
    }
}
