package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `src/main/resources/vault/README.md` is seeded into the user's own repository (#258 —
 * formerly a copied template), so it is the first thing they read — and it is the one
 * document this repository's own path guard cannot check, because every path in it is
 * relative to a repository that lives elsewhere (see `scripts/guards.sh`, "WHICH TREES").
 *
 * It drifted exactly as an unchecked document does (#96): it promised five Dataview
 * dashboard notes nothing generates, a `SolutionTest.java` the server does not write — the
 * file is `RunnerTest.java` — and a `meta.json` that has never existed. A reader followed
 * those into an empty vault.
 *
 * So the invariant is pinned here instead: every file name the template's structure block
 * mentions must be one the server actually writes.
 */
class RecordRepositoryTemplateTest {
    @Test
    fun `the template names only files the server writes`() {
        val stale = namesInStructureBlock() - written()

        stale.shouldBeEmpty()
    }

    /** The other direction: the artifacts a user most needs to find must be listed. */
    @Test
    fun `the template names the artifacts a user has to find`() {
        namesInStructureBlock() shouldContainAll setOf("README.md", "notes.md", "examples.json", "attempts/")
    }

    /**
     * The tag map is the one artifact whose whole purpose is to be *looked at*, and a user who
     * does not know it exists will not open the graph. Named separately from the set above
     * because that set is about finding your own code; this is about finding the map (#229).
     */
    @Test
    fun `the template names the tag map, because being seen is the point of it`() {
        namesInStructureBlock() shouldContain "tags/<tag>.md"
    }

    /**
     * What the server writes, spelled out rather than imported. A set shared with the
     * production constants would agree with whatever production says and prove nothing —
     * the same reason `FileDerivedArtifactsTest` spells out the stale-runner names. Adding
     * a file to the record repository is meant to cost a line here.
     */
    private fun written(): Set<String> = setOf(
        "README.md",
        // Untouched by the server by design, but the template ships it as the place for the
        // user's own notes, so naming it is correct.
        "notes.md",
        "examples.json",
        "log/submissions.jsonl",
        "attempts/",
        // Seeded once if absent, then the reader's (#254). Named here for the same reason the
        // five phantom notes were taken out: the structure block is what a reader follows.
        "dashboard.base",
        // The vault's tag map (#229): one note per catalogued tag, including the untouched ones.
        "tags/<tag>.md",
        // Per language, so the template names the pattern rather than seven extensions.
        "Solution.<ext>",
        "runner_test.<ext>",
        "RunnerTest.java",
        "runner_test.csproj",
        "NNN.<ext>",
        "NNN.raw.jsonl",
    )

    /**
     * Every token in the fenced block that looks like a file name — a dotted name, or a
     * directory ending in `/`. Prose outside the block is left alone: it is where the
     * template is allowed to talk about what does *not* exist yet, as it now does for the
     * dashboard notes.
     */
    private fun namesInStructureBlock(): Set<String> {
        val block = TEMPLATE.readText().substringAfter("```").substringBefore("```")
        return block.lines()
            .mapNotNull { line -> line.split(Regex("""\s+""")).firstOrNull { NAME.matches(it) } }
            .toSet()
    }

    private fun Path.readText(): String = Files.readString(this)

    private companion object {
        val TEMPLATE: Path = Path.of("src/main/resources/vault/README.md")

        /** `foo.bar`, `foo/`, or a `<ext>`-style pattern — never a prose word. */
        val NAME = Regex("""[\w.<>/-]*[\w>]\.[\w<>]+|[\w-]+/""")
    }
}
