package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.support.fixtures.aCatalogEntry
import com.brokenfinger.tracker.support.fixtures.aCatalogOf
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

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

        attachment(RecordingArtifacts(written)).refreshTagMap()

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
     * Records what it was asked to write and does the real work for everything else, so the
     * assertion is on the argument rather than on an interaction — a spy here would prove the
     * method was called and not what it was called with.
     */
    private inner class RecordingArtifacts(private val seen: MutableList<TagCount>) : DerivedArtifacts {
        private val delegate = FileDerivedArtifacts(root, store())

        override fun writeCode(record: SubmissionRecord, code: String) = delegate.writeCode(record, code)

        override fun writeRunner(record: SubmissionRecord, code: String) = delegate.writeRunner(record, code)

        override fun writeReadme(records: List<SubmissionRecord>) = delegate.writeReadme(records)

        override fun writeTagNotes(counts: List<TagCount>) {
            seen += counts
        }
    }
}
