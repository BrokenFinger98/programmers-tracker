# Tag Map in the Vault — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write one note per catalog tag into the record repository so Obsidian's graph shows which problem types have never been met.

**Architecture:** A pure calculator in `domain/calc` joins the shipped catalog against the submission log and returns per-tag counts — the same shape `CatalogBrowse` already has, reusing its `CatalogSummary`. A writer in `adapter/store` renders those counts as `tags/<tag>.md`. Problem READMEs gain a wikilink line so the graph has edges. Startup writes every note; each record rewrites only its own tags.

**Tech Stack:** Kotlin (JVM 25), JUnit 5 + Kotest assertions, no new dependencies.

Spec: `docs/superpowers/specs/2026-08-12-tag-map-vault-design.md` · Issue #229

---

## File structure

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/TagCoverage.kt` | **create** — pure calculator + `TagCount` |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/TagCoverageTest.kt` | **create** — unit, zero mocks |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/TagNotes.kt` | **create** — renders and writes `tags/<tag>.md` |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/store/TagNotesTest.kt` | **create** — layer |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/RecordLayout.kt` | **modify** — `tagNote(tag)` |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/ProblemReadme.kt` | **modify** — the wikilink line |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/store/ProblemReadmeTest.kt` | **modify** |
| `src/main/kotlin/com/brokenfinger/tracker/application/CodeAttachment.kt` | **modify** — port method + call after the README |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/FileDerivedArtifacts.kt` | **modify** — implement it |
| `src/main/kotlin/com/brokenfinger/tracker/application/StartupReconciliation.kt` | **modify** — write every note at boot |
| `template/ps-records/README.md` | **modify** — name `tags/` |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/store/RecordRepositoryTemplateTest.kt` | **modify** — feed the guard |

---

## Task 1: The calculator

**Files:**
- Create: `src/main/kotlin/com/brokenfinger/tracker/domain/calc/TagCoverage.kt`
- Test: `src/test/kotlin/com/brokenfinger/tracker/domain/calc/TagCoverageTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.brokenfinger.tracker.domain.calc

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks, two in-memory snapshots in and counts out (dev rules §3).
 *
 * The case that matters most is the last one: a tag with no records at all still gets a row.
 * That row is the isolated node in Obsidian's graph, and it is the whole reason the map exists
 * (#229) — a view built only from your own records cannot show a type you never met.
 */
class TagCoverageTest {
    private fun problem(id: Long, vararg tags: String) =
        CatalogSummary(lessonId = id, title = "p$id", level = 0, part = null, acceptanceRate = null, tags = tags.toList())

    @Test
    fun `a problem with two tags counts under both`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp", "math")),
            passedIds = setOf(1L),
            submittedIds = setOf(1L),
        )

        counts shouldContainExactly listOf(
            TagCount(tag = "dp", catalogTotal = 1, attempted = 1, solved = 1),
            TagCount(tag = "math", catalogTotal = 1, attempted = 1, solved = 1),
        )
    }

    @Test
    fun `a submit without a pass is attempted and not solved`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp")),
            passedIds = emptySet(),
            submittedIds = setOf(1L),
        )

        counts.single() shouldBe TagCount(tag = "dp", catalogTotal = 2, attempted = 1, solved = 0)
    }

    /** The graph's hole. Without this row an untouched type is absent rather than isolated. */
    @Test
    fun `a tag with no records at all still gets a row`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp"), problem(2, "dp"), problem(3, "tsp")),
            passedIds = emptySet(),
            submittedIds = emptySet(),
        )

        counts shouldContainExactly listOf(
            TagCount(tag = "dp", catalogTotal = 2, attempted = 0, solved = 0),
            TagCount(tag = "tsp", catalogTotal = 1, attempted = 0, solved = 0),
        )
    }

    @Test
    fun `a catalogued problem carrying no tag contributes nowhere`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1), problem(2, "dp")),
            passedIds = setOf(1L, 2L),
            submittedIds = setOf(1L, 2L),
        )

        counts.single() shouldBe TagCount(tag = "dp", catalogTotal = 1, attempted = 1, solved = 1)
    }

    /**
     * A record for a lesson the catalog does not describe cannot raise any tag's count, because
     * the count is over catalogued problems. Stated as a test because the opposite — inventing a
     * tag row from a record — is how a snapshot we do not own starts growing entries.
     */
    @Test
    fun `a submitted lesson outside the catalog raises nothing`() {
        val counts = TagCoverage.of(
            catalogued = listOf(problem(1, "dp")),
            passedIds = setOf(999L),
            submittedIds = setOf(999L),
        )

        counts.single() shouldBe TagCount(tag = "dp", catalogTotal = 1, attempted = 0, solved = 0)
    }

    @Test
    fun `rows come back in a stable order so a rewrite produces the same bytes`() {
        val catalogued = listOf(problem(1, "math"), problem(2, "dp"), problem(3, "arithmetic"))

        val counts = TagCoverage.of(catalogued, passedIds = emptySet(), submittedIds = emptySet())

        counts.map { it.tag } shouldContainExactly listOf("arithmetic", "dp", "math")
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*TagCoverageTest*'`
Expected: FAIL — `Unresolved reference: TagCoverage`

- [ ] **Step 3: Write the calculator**

```kotlin
package com.brokenfinger.tracker.domain.calc

/**
 * How much of each catalogued tag has been met — a pure calculator over two in-memory
 * snapshots (dev rules §3), the same shape and the same inputs as [CatalogBrowse].
 *
 * **It counts and names nothing.** No ratio carries a verdict, no row is marked weak, and the
 * order is alphabetical rather than "most neglected first" — an ordering by neglect would be
 * the judgement the server is forbidden to make
 * ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]). What a gap is belongs to
 * whoever reads the map.
 *
 * `attempted` counts **submits**, matching `list_problems`' own `attempted` status. Counting
 * runs too would arguably be closer to "met the type", and it would make two surfaces answer
 * the same question with different numbers — the confusion #214 had to disclose its way out of.
 */
object TagCoverage {
    /**
     * @param catalogued every problem the snapshot describes
     * @param passedIds lessons with at least one passing submit
     * @param submittedIds lessons with at least one submit
     */
    fun of(
        catalogued: List<CatalogSummary>,
        passedIds: Set<Long>,
        submittedIds: Set<Long>,
    ): List<TagCount> = catalogued
        .flatMap { problem -> problem.tags.map { it to problem.lessonId } }
        .groupBy({ it.first }, { it.second })
        .map { (tag, lessons) -> countOf(tag, lessons, passedIds, submittedIds) }
        .sortedBy { it.tag }

    private fun countOf(
        tag: String,
        lessons: List<Long>,
        passedIds: Set<Long>,
        submittedIds: Set<Long>,
    ) = TagCount(
        tag = tag,
        catalogTotal = lessons.size,
        attempted = lessons.count { it in submittedIds },
        solved = lessons.count { it in passedIds },
    )
}

/**
 * One tag's standing. Every field is a count; there is deliberately no ratio and no flag,
 * because a stored ratio invites a stored threshold and a threshold is a verdict.
 */
data class TagCount(
    val tag: String,
    val catalogTotal: Int,
    val attempted: Int,
    val solved: Int,
)
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew test --tests '*TagCoverageTest*'`
Expected: PASS, 6 tests

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/brokenfinger/tracker/domain/calc/TagCoverage.kt \
        src/test/kotlin/com/brokenfinger/tracker/domain/calc/TagCoverageTest.kt
git commit -m "feat(tags): count how much of each catalogued tag has been met

A pure calculator over the catalog and the submission log, the same shape as
CatalogBrowse. It counts and names nothing: no ratio, no flag, and an
alphabetical order rather than one by neglect.

Refs #229"
```

---

## Task 2: The note file

**Files:**
- Modify: `src/main/kotlin/com/brokenfinger/tracker/adapter/store/RecordLayout.kt`
- Create: `src/main/kotlin/com/brokenfinger/tracker/adapter/store/TagNotes.kt`
- Test: `src/test/kotlin/com/brokenfinger/tracker/adapter/store/TagNotesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.TagCount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TagNotesTest {
    @TempDir
    lateinit var root: Path

    private fun notes() = TagNotes(RecordLayout(root))

    @Test
    fun `writes one note per tag, with the denominator that makes it honest`() {
        notes().write(listOf(TagCount("dp", catalogTotal = 38, attempted = 5, solved = 3)))

        val text = Files.readString(root.resolve("tags/dp.md"))
        text shouldContain "tag: dp"
        text shouldContain "catalogTotal: 38"
        text shouldContain "attempted: 5"
        text shouldContain "solved: 3"
    }

    /**
     * The untouched tag is the point of the map: its note exists so the graph has a node to
     * leave isolated.
     */
    @Test
    fun `an untouched tag gets its note too`() {
        notes().write(listOf(TagCount("tsp", catalogTotal = 1, attempted = 0, solved = 0)))

        Files.exists(root.resolve("tags/tsp.md")) shouldBe true
    }

    /** Rewriting an unchanged map must leave git nothing to see. */
    @Test
    fun `a second write of the same counts produces identical bytes`() {
        val counts = listOf(TagCount("dp", 38, 5, 3), TagCount("math", 78, 1, 0))

        notes().write(counts)
        val first = Files.readAllBytes(root.resolve("tags/dp.md"))
        notes().write(counts)

        Files.readAllBytes(root.resolve("tags/dp.md")) shouldBe first
    }

    /** A tag name is a file name, and the catalog's are ours to trust no further than that. */
    /**
     * The file name is a path and the `tag:` field is the datum. They differ on purpose for a
     * tag the filesystem would not take verbatim, and only the field may be compared against a
     * record's tags.
     */
    @Test
    fun `a tag the filesystem would not take keeps its spelling in the field`() {
        notes().write(listOf(TagCount("prime factorization", catalogTotal = 2, attempted = 0, solved = 0)))

        val text = Files.readString(root.resolve("tags/prime-factorization.md"))
        text shouldContain "tag: prime factorization"
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*TagNotesTest*'`
Expected: FAIL — `Unresolved reference: TagNotes`

- [ ] **Step 3: Add the layout path**

In `RecordLayout.kt`, add this method beside `submissionLog()`:

```kotlin
    /** One note per catalogued tag, the vault's map of what has and has not been met (#229). */
    fun tagNote(tag: String): Path = root.resolve(TAGS).resolve("${slugOf(tag)}.md")
```

and add to the `companion object`, beside `PROBLEMS`:

```kotlin
        private const val TAGS = "tags"
```

`slugOf` already exists at `RecordLayout.kt:101` — but it is **private inside the companion
object**, so the instance method above can call it as `slugOf(tag)` only because Kotlin resolves
companion members from instance scope. That compiles. Do **not** add a second sanitiser: two of
them is how a tag and its note stop agreeing on a name.

One consequence to know rather than discover: `slugOf` lowercases and collapses everything that
is not a letter or digit to a hyphen, so `prime factorization` becomes `prime-factorization` and
`dp_digit` becomes `dp-digit`. The note's `tag:` frontmatter keeps the **original** spelling —
the file name is a path, the field is the datum, and only the field should be compared against a
record's tags.

- [ ] **Step 4: Write the note renderer**

```kotlin
package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.TagCount
import java.nio.file.Files

/**
 * The vault's tag map — `tags/<tag>.md`, one per catalogued tag (#229, design
 * `2026-08-12-tag-map-vault`).
 *
 * **Overwritten whole, every time**, exactly as [ProblemReadme] is: human prose belongs in
 * `notes.md`, which the server never touches. Output depends only on the counts it is given,
 * so rewriting an unchanged map produces identical bytes and leaves git nothing to commit.
 *
 * The note states three counts and stops. It does not rank, flag or advise — that boundary is
 * the whole reason this exists rather than the `_weakness.md` the design once listed
 * ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]).
 */
class TagNotes(private val layout: RecordLayout) {
    fun write(counts: List<TagCount>) = counts.forEach { write(it) }

    private fun write(count: TagCount) {
        val file = layout.tagNote(count.tag)
        Files.createDirectories(file.parent)
        Files.writeString(file, render(count))
    }

    private fun render(count: TagCount): String =
        """
        |---
        |tag: ${count.tag}
        |catalogTotal: ${count.catalogTotal}
        |attempted: ${count.attempted}
        |solved: ${count.solved}
        |---
        |
        |# ${count.tag}
        |
        |Met ${count.attempted} of ${count.catalogTotal}, passed ${count.solved}.
        |
        """.trimMargin()
}
```

- [ ] **Step 5: Run it and watch it pass**

Run: `./gradlew test --tests '*TagNotesTest*'`
Expected: PASS, 4 tests

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/brokenfinger/tracker/adapter/store/TagNotes.kt \
        src/main/kotlin/com/brokenfinger/tracker/adapter/store/RecordLayout.kt \
        src/test/kotlin/com/brokenfinger/tracker/adapter/store/TagNotesTest.kt
git commit -m "feat(tags): write one note per catalogued tag

Overwritten whole like the problem README, so an unchanged map rewrites the
same bytes. Three counts and no ranking.

Refs #229"
```

---

## Task 3: Problem READMEs link to their tags

Without this the notes exist and the graph has no edges — the map would be 83 isolated nodes.

**Files:**
- Modify: `src/main/kotlin/com/brokenfinger/tracker/adapter/store/ProblemReadme.kt`
- Test: `src/test/kotlin/com/brokenfinger/tracker/adapter/store/ProblemReadmeTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `ProblemReadmeTest`:

```kotlin
    /**
     * Obsidian's graph draws links, not hashtags. The inline `#dp` drives the tag pane and
     * search and cannot be a denominator; the wikilink is what puts an edge between a problem
     * and its tag note (#229). Both stay — they serve different features.
     */
    @Test
    fun `the page links to each of its tag notes`() {
        val file = write(listOf(aSubmissionRecord(tags = listOf("dp", "math"))))

        val text = Files.readString(file)
        text shouldContain "[[tags/dp]]"
        text shouldContain "[[tags/math]]"
        text shouldContain "#dp"
    }

    /** A problem outside the shipped catalog carries no tags, and no link is invented for it. */
    @Test
    fun `a page for an uncatalogued problem links to no tag`() {
        val file = write(listOf(aSubmissionRecord(tags = emptyList())))

        Files.readString(file) shouldNotContain "[[tags/"
    }
```

Add the import if the file lacks it:

```kotlin
import io.kotest.matchers.string.shouldNotContain
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*ProblemReadmeTest*'`
Expected: FAIL — the rendered page has no `[[tags/dp]]`

- [ ] **Step 3: Render the link line**

In `ProblemReadme.kt`, change `heading` so the tag links follow the hashtag line. The existing
`obsidianTags(records)` stays untouched; add beside it:

```kotlin
    /**
     * Wikilinks, in addition to the hashtags above, because Obsidian's graph draws links and
     * its tag pane reads hashtags — two features, two mechanisms, and dropping either loses
     * something that works (#229).
     *
     * Empty for a problem the catalog does not describe: an invented link would create a tag
     * note for a tag no catalogued problem carries.
     */
    private fun tagLinks(records: List<SubmissionRecord>): String {
        val tags = tagsOf(records).orEmpty()
        if (tags.isEmpty()) return ""
        return "\nTags: " + tags.joinToString(" ") { "[[tags/$it]]" } + "\n"
    }
```

and append it in `heading`:

```kotlin
        return "\n# $title\n\n${obsidianTags(records)}${tagLinks(records)}"
```

> `tagsOf(records)` already exists in this class — it is what the frontmatter's `tags:` field
> uses. Reuse it so the frontmatter and the links can never disagree.

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew test --tests '*ProblemReadmeTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/brokenfinger/tracker/adapter/store/ProblemReadme.kt \
        src/test/kotlin/com/brokenfinger/tracker/adapter/store/ProblemReadmeTest.kt
git commit -m "feat(tags): link each problem page to its tag notes

Obsidian's graph draws links; the inline hashtags drive the tag pane. Both
stay, because dropping either loses a feature that works today.

Refs #229"
```

---

## Task 4: The port and the adapter

**Files:**
- Modify: `src/main/kotlin/com/brokenfinger/tracker/application/CodeAttachment.kt` (the `DerivedArtifacts` interface at the bottom)
- Modify: `src/main/kotlin/com/brokenfinger/tracker/adapter/store/FileDerivedArtifacts.kt`

- [ ] **Step 1: Add the port method**

In the `DerivedArtifacts` interface, beside `writeReadme`:

```kotlin
    /**
     * The vault's tag map, rewritten for the tags given. Called with one problem's tags after
     * a record, and with every catalogued tag at startup (#229).
     */
    fun writeTagNotes(counts: List<TagCount>)
```

with the import:

```kotlin
import com.brokenfinger.tracker.domain.calc.TagCount
```

- [ ] **Step 2: Implement it**

In `FileDerivedArtifacts`, beside the existing `readme` field:

```kotlin
    private val tagNotes = TagNotes(layout)
```

and the method:

```kotlin
    override fun writeTagNotes(counts: List<TagCount>) = tagNotes.write(counts)
```

- [ ] **Step 3: Run the build to catch every implementor**

Run: `./gradlew build -x test`
Expected: FAIL if a test double implements `DerivedArtifacts` without the new method — add
`override fun writeTagNotes(counts: List<TagCount>) = Unit` to each such double, then re-run
until it compiles.

- [ ] **Step 4: Run the whole suite**

Run: `./scripts/test.sh`
Expected: BUILD SUCCESSFUL — nothing calls the new method yet, so behaviour is unchanged.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(tags): a port for the tag map

No caller yet; the next commit wires the two that exist.

Refs #229"
```

---

## Task 5: Call it — after a record, and at startup

**Files:**
- Modify: `src/main/kotlin/com/brokenfinger/tracker/application/CodeAttachment.kt`
- Modify: `src/main/kotlin/com/brokenfinger/tracker/application/StartupReconciliation.kt`
- Test: `src/test/kotlin/com/brokenfinger/tracker/application/CodeAttachmentTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `CodeAttachmentTest` (or the test that already covers `attached()` writing the README):

```kotlin
    /**
     * The tag map is derived, so it is written after the record and its failure must not cost
     * one — the posture writeReadme and writeRunner already take.
     */
    @Test
    fun `attaching code refreshes the tag notes of that problem only`() {
        val written = mutableListOf<TagCount>()
        val artifacts = artifactsRecording(written)

        attach(artifacts, aSubmissionRecord(lessonId = 120804, tags = listOf("arithmetic")))

        written.map { it.tag } shouldContainExactly listOf("arithmetic")
    }
```

> `artifactsRecording` is a local test double implementing `DerivedArtifacts` whose
> `writeTagNotes` appends to the list and whose other methods are no-ops. Write it in the test
> file; do not reach for MockK, since the assertion is on what was passed rather than on an
> interaction.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*CodeAttachmentTest*'`
Expected: FAIL — nothing calls `writeTagNotes`

- [ ] **Step 3: Call it after the README**

In `CodeAttachment.attached(...)`, after `artifacts.writeReadme(lessonHistory(corrected.lessonId))`:

```kotlin
        artifacts.writeTagNotes(coverageOf(corrected.tags))
```

and add the helper, which recomputes from the log rather than incrementing:

```kotlin
    /**
     * Recomputed from the log, never incremented. A held counter is a second authority that a
     * reconciliation or a restart can put out of step, and only the tags of the record just
     * written are refreshed — the rest are unchanged and rewriting them would be noise.
     */
    private fun coverageOf(tags: List<String>): List<TagCount> {
        if (tags.isEmpty()) return emptyList()
        val submits = RecordHistory.of(store.read()).filter { it.action == GradingAction.SUBMIT }
        return TagCoverage.of(
            catalogued = catalog.all().map { it.toSummary() },
            passedIds = submits.filter { it.verdict == Verdict.PASS }.map { it.lessonId }.toSet(),
            submittedIds = submits.map { it.lessonId }.toSet(),
        ).filter { it.tag in tags }
    }
```

> `toSummary()` already exists in `RecordQuery`. Rather than duplicating it, move it to a shared
> place both can use — `CatalogEntry.toSummary()` as an extension in `application`, beside
> `CatalogEntry` itself. Update `RecordQuery.browse` to use the moved one. A second copy of a
> mapping is a second thing to keep in step.

`CodeAttachment` needs a `ProblemCatalog` constructor parameter if it has none; add it and wire
it in `CaptureConfiguration` where `CodeAttachment` is built.

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew test --tests '*CodeAttachmentTest*'`
Expected: PASS

- [ ] **Step 5: Write every note at startup**

In `StartupReconciliation`, after the code attachment pass, add:

```kotlin
    /**
     * Every tag note, not only the missing ones. Startup is also when reconciliation recovers
     * records a crash left behind, and those change counts — refreshing only what is absent
     * would leave a note disagreeing with the log until the next grading happened to touch that
     * tag. Identical bytes are not a change, so git sees nothing for the untouched ones.
     */
    private fun writeTagMap() {
        runCatching { artifacts.writeTagNotes(coverage()) }
            .onFailure { logger.warn("The tag map could not be written; records are unaffected", it) }
    }
```

called at the end of the startup routine, with `coverage()` built exactly as `coverageOf` above
but without the `filter`.

- [ ] **Step 6: Run every gate**

Run: `./scripts/check.sh && ./scripts/test.sh && ./scripts/build.sh && ./scripts/guards.sh`
Expected: all exit 0

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(tags): write the map at startup and after each record

Startup writes every note, because reconciliation recovers records there and
refreshing only the missing ones would leave a note disagreeing with the log.
A record refreshes its own tags and no others.

Counts are recomputed from the submission log rather than incremented: a held
counter is what a restart puts out of step.

Refs #229"
```

---

## Task 6: Feed the guard

The test that caught the dashboard drift at #96 and the design drift at #227 enforces that the
template names only files the server writes. Adding a file without adding it here drifts in the
opposite direction — the server writes something the user's own README never mentions.

**Files:**
- Modify: `template/ps-records/README.md`
- Modify: `src/test/kotlin/com/brokenfinger/tracker/adapter/store/RecordRepositoryTemplateTest.kt`

- [ ] **Step 1: Add `tags/` to the test's written set**

In `RecordRepositoryTemplateTest.written()`:

```kotlin
        "tags/",
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*RecordRepositoryTemplateTest*'`
Expected: PASS still — the set grew but the template block has not. Now add the assertion that
makes it fail:

```kotlin
    @Test
    fun `the template names the tag map, because the graph is why it exists`() {
        namesInStructureBlock() shouldContain "tags/"
    }
```

Run again. Expected: FAIL — the template block does not mention `tags/`.

- [ ] **Step 3: Name it in the template**

In `template/ps-records/README.md`, add to the structure block:

```
├── tags/                    one note per problem type, with how many of it you have met
```

and to the Obsidian section, replacing the sentence about the unwritten dashboards:

```markdown
Open this folder as a vault and the graph shows your problem types: each problem links to its
tags, and a tag you have never submitted against sits there as an isolated node. The note says
how many problems the catalog has for it, so a lone `tsp` (one problem anywhere) reads
differently from a lone `dp` (38).

What counts as a gap is yours to decide — the server writes the numbers and never names a
weakness.
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew test --tests '*RecordRepositoryTemplateTest*'`
Expected: PASS

- [ ] **Step 5: Update the Korean twin and resync its marker**

`template/ps-records/README.ko.md` is one of the five pages that ship a Korean twin. Translate
the section above, then:

```bash
./scripts/guards.sh
```

It fails with the expected blob hash; put that hash in the twin's `translated-from` line and
re-run until it passes.

- [ ] **Step 6: Run every gate and commit**

```bash
./scripts/check.sh && ./scripts/test.sh && ./scripts/build.sh && ./scripts/guards.sh
git add -A
git commit -m "docs(template): name the tag map where a user will read it

The guard that caught #96 and #227 enforces that the template names only files
the server writes. This adds the file, so it adds the name — otherwise the
drift runs the other way.

Closes #229"
```

---

## Verify it against the real thing

The constitution's rule is that a feature whose essence is external interaction is done only
once it has actually run. This one writes into a live record repository, so:

- [ ] Rebuild and restart: `SOURCE_COMMIT=$(git rev-parse --short HEAD) docker compose build tracker && docker compose up -d tracker`
- [ ] Confirm `tags/` appeared with one note per catalogued tag: `ls ~/Desktop/ps-records/tags | wc -l` — expect 83 against today's catalog
- [ ] Read one note that should be empty: `cat ~/Desktop/ps-records/tags/dp.md` — expect `catalogTotal: 38`, `attempted: 0`, `solved: 0`
- [ ] Read one that should not: `cat ~/Desktop/ps-records/tags/arithmetic.md` — expect non-zero counts
- [ ] Restart once more and confirm `git -C ~/Desktop/ps-records status --porcelain` shows nothing new — identical bytes, no churn
- [ ] Open the vault in Obsidian and confirm the graph shows tag nodes with isolated ones among them
