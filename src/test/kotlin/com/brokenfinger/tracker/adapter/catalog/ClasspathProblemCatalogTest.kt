package com.brokenfinger.tracker.adapter.catalog

import com.brokenfinger.tracker.domain.LessonId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test

/**
 * The shipped catalog, read the way the application reads it.
 *
 * These assert against the **real resource** rather than a fixture. A fixture would test the
 * loader and say nothing about the file that actually ships — and the file is the point: it
 * was assembled once by a process nobody will run again, so a corruption in it would survive
 * every test that only checked a stand-in.
 */
class ClasspathProblemCatalogTest {
    private val catalog = ClasspathProblemCatalog.load()

    @Test
    fun `ships every problem the list API reported`() {
        catalog.size() shouldBe 689
    }

    @Test
    fun `finds a problem by its lesson id`() {
        val entry = catalog.find(LessonId(181951))

        entry.shouldNotBeNullAndHave(title = "a와 b 출력하기", level = 0)
    }

    @Test
    fun `a lesson that is not in the catalog is absent rather than invented`() {
        catalog.find(LessonId(999999999)).shouldBeNull()
    }

    /**
     * The catalog is reference data about somebody else's site, so it goes stale by
     * construction — a problem added after it was built is simply missing. Callers must
     * treat absence as ordinary, which is why nothing here throws.
     */
    @Test
    fun `tags of an unknown lesson are empty, not an error`() {
        catalog.tagsOf(LessonId(999999999)).shouldBeEmpty()
    }

    @Test
    fun `every entry carries a title, because a catalog nobody can read cannot be reviewed`() {
        catalog.all().forEach { it.title.shouldNotBeBlank() }
    }

    /**
     * The labels are only worth having if they come from the vocabulary the project adopted.
     * A tag outside it means the catalog and the vocabulary have drifted, and a weakness
     * profile built on it would aggregate two different things under one name.
     */
    @Test
    fun `every tag exists in the shipped solved ac vocabulary`() {
        val vocabulary = ClasspathProblemCatalog.vocabulary()

        catalog.all().forEach { entry ->
            entry.tags.forEach { tag -> vocabulary shouldContain tag }
        }
    }

    @Test
    fun `the vocabulary is the measured size rather than the one documents used to claim`() {
        ClasspathProblemCatalog.vocabulary().size shouldBe 229
    }

    @Test
    fun `every entry carries at least one tag`() {
        catalog.all().count { it.tags.isEmpty() } shouldBe 0
    }

    /** Level drives recommendation more than the tag does; an entry without one is unusable. */
    @Test
    fun `every entry carries a level`() {
        catalog.all().count { it.level == null } shouldBe 0
    }

    @Test
    fun `lesson ids are unique, so a lookup can never be ambiguous`() {
        catalog.all().map { it.id }.toSet().size shouldBe catalog.size()
    }

    /**
     * How the file was produced travels with it. The collection cost is the part of this
     * feature that touches somebody else's servers, and a claim about it that lives only in
     * a commit message is one nobody will find.
     */
    @Test
    fun `the catalog states how it was built`() {
        val provenance = catalog.provenance()

        provenance.keys shouldContain "statements"
        provenance.values.forEach { it.shouldNotBeBlank() }
    }

    private fun CatalogEntry?.shouldNotBeNullAndHave(title: String, level: Int) {
        this shouldBe CatalogEntry(
            id = this!!.id,
            title = title,
            level = level,
            partTitle = partTitle,
            acceptanceRate = acceptanceRate,
            tags = tags,
        )
    }
}
