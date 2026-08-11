package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orphaned frames were written and then never mentioned again (#169). These pin the query
 * that makes them reportable — deliberately a count and a path, never a parse.
 */
class FileRawSessionLogOrphansTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a repository that has orphaned nothing reports nothing`() {
        log().orphans().shouldBeEmpty()
    }

    @Test
    fun `frames orphaned for a lesson are reported with their count`() {
        val log = log()
        log.orphaned(120802, """{"identifier":"…","message":{"action":"submit","type":"testcase"}}""")
        log.orphaned(120802, """{"identifier":"…","message":{"action":"submit","type":"finish"}}""")

        val orphans = log.orphans()

        orphans.size shouldBe 1
        orphans.single().lessonId shouldBe 120802
        orphans.single().frames shouldBe 2
        Files.exists(orphans.single().path) shouldBe true
    }

    @Test
    fun `several lessons are reported in lesson order`() {
        val log = log()
        log.orphaned(181946, """{"message":{"action":"submit","type":"finish"}}""")
        log.orphaned(120802, """{"message":{"action":"submit","type":"finish"}}""")

        log.orphans().map { it.lessonId } shouldBe listOf(120802L, 181946L)
    }

    /**
     * The orphan directory sits inside the work-list directory, so anything else that lands
     * there must not be counted as a lesson. A stray file reporting as lesson 0 would put a
     * hole in the history that never existed.
     */
    @Test
    fun `a file that is not named after a lesson is not reported as one`() {
        val log = log()
        log.orphaned(120802, """{"message":{"action":"submit","type":"finish"}}""")
        val orphanDirectory = log.orphans().single().path.parent
        Files.writeString(orphanDirectory.resolve("notes.txt"), "left by a person\n")
        Files.writeString(orphanDirectory.resolve(".DS_Store"), "\n")

        log.orphans().map { it.lessonId } shouldBe listOf(120802L)
    }

    private fun log() = FileRawSessionLog.under(root)
}
