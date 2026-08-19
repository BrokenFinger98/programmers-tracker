package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The record repository must ignore the state directory, because the state moved inside it
 * (#126) and reconciliation is `git add --all`.
 *
 * `.ps/raw/recorded/` holds one session per **run**, and a run "gets pressed dozens of times
 * while writing code" — committing them is the repository inflation
 * [[decisions/2026-08-08-run-raw-sessions]] weighed and rejected. Repositories created before
 * #122 carry a `.gitignore` that names `.ps/session` and `.ps/catalog.json` one at a time and
 * ignores none of this, and there is no upgrade path for a file the user owns other than
 * adding the line.
 */
class RecordRepositoryIgnoresTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `adds the rule to a gitignore that predates the state directory`() {
        write(".gitignore", "# Credentials\n.ps/session\n.ps/cookies*\n")

        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain("\n.ps/\n")
    }

    @Test
    fun `keeps what the file already said`() {
        write(".gitignore", "# Credentials\n.ps/session\n.DS_Store\n")

        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain(".DS_Store")
    }

    @Test
    fun `writes a gitignore to a repository that has none`() {
        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain(".ps/")
    }

    /**
     * Startup runs on every boot, so a second pass must not append a second copy — a rule
     * appended once per restart is how a file grows to a thousand identical lines.
     */
    @Test
    fun `is idempotent across boots`() {
        RecordRepositoryIgnores(root).ensure()
        val once = read(".gitignore")

        RecordRepositoryIgnores(root).ensure()

        read(".gitignore") shouldBe once
    }

    /**
     * Recognises the rule however the user wrote it. Appending a duplicate would be harmless
     * to git and noisy to a human, and the whole point is to leave their file alone.
     */
    @Test
    fun `leaves a file that already ignores the directory without the trailing slash`() {
        write(".gitignore", "# mine\n.ps\n")

        RecordRepositoryIgnores(root).ensure()

        val text = read(".gitignore")
        text.shouldStartWith("# mine\n.ps\n")
        text.shouldNotContain(".ps/")
    }

    /**
     * The vault's **window layout** is not records, and `git add --all` cannot tell the difference.
     * On the owner's repository four commits carried `.obsidian/`, and one — the 23:00 backup —
     * carried **nothing else**, under a message that says it reconciled records (#234).
     */
    @Test
    fun `ignores the vault's window layout`() {
        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain(".obsidian/workspace.json")
        read(".gitignore").shouldContain(".obsidian/workspace-mobile.json")
    }

    /**
     * And **only** that file. The rule used to take the whole directory, which threw away
     * `graph.json` — the colour groups the reader built — to escape a layout file that changes
     * because the vault was opened. A tool whose argument is that nothing is lost should not be
     * dropping somebody's configuration from their own backup (#306).
     */
    @Test
    fun `leaves the graph settings and the rest of the vault's configuration versioned`() {
        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldNotContain("\n.obsidian/\n")
    }

    /**
     * And the other editor's, for the reason above rather than for Obsidian's sake (#304). The
     * argument was never about Obsidian: `git add --all` cannot tell a window layout from a
     * record. This is a Kotlin project, so a vault opened in IntelliJ is not an edge case — and
     * the owner's repository had `.idea/` tracked and pushed until this rule existed.
     */
    @Test
    fun `also ignores the other editor's state`() {
        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain(".idea/")
    }

    /**
     * The half of that editor's state the rule above cannot reach (#323). IntelliJ writes
     * `<project>.iml` at the **root**, outside `.idea/`, and it lists every problem directory it
     * has indexed — so it outlives the records it names. Measured on a repository whose history
     * had just been cleared: the `.iml` still published four problem titles, two of them already
     * deleted. A wipe that leaves a list of what was wiped is not a wipe.
     */
    @Test
    fun `ignores the module file IntelliJ writes outside its own directory`() {
        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain("*.iml")
    }

    /**
     * Neither rule is an ancestor of the other, so the ancestor check that stops the server
     * arguing with a broader decision (#306) must not swallow this one: a reader who wrote
     * `.idea/` said nothing about a file at the root.
     */
    @Test
    fun `a repository that already ignores the editor's directory still gains the module file`() {
        Files.writeString(root.resolve(".gitignore"), ".idea/\n")

        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain("*.iml")
    }

    /**
     * Both moved here from the template's .gitignore when the template retired (#258): a
     * template reaches only repositories that do not exist yet.
     */
    @Test
    fun `also ignores the lock file and finder noise`() {
        RecordRepositoryIgnores(root).ensure()

        val text = read(".gitignore")
        text.shouldContain(".programmers-tracker.lock")
        text.shouldContain(".DS_Store")
    }

    /**
     * One rule missing is one rule added; the other is left exactly as the user wrote it — and a
     * reader who wrote `.obsidian` meant the **whole directory**, so appending
     * `.obsidian/workspace.json` underneath would be the server arguing with a broader decision
     * they already made (#306).
     */
    @Test
    fun `adds only the rule that is missing, and never under a directory already ignored`() {
        write(".gitignore", "# mine\n.obsidian\n")

        RecordRepositoryIgnores(root).ensure()

        val text = read(".gitignore")
        text.shouldContain("\n.ps/\n")
        text.shouldNotContain(".obsidian/")
    }

    /**
     * A record repository on a read-only mount is a broken setup, but it is not a reason to
     * refuse to record: the capture is the thing that cannot be replayed (protocol §11), and
     * a grading lost to a `.gitignore` write would be the wrong trade entirely.
     */
    @Test
    fun `does not throw when the file cannot be written`() {
        RecordRepositoryIgnores(root.resolve("no/such/directory")).ensure()
    }

    private fun write(name: String, text: String) = Files.writeString(root.resolve(name), text)

    private fun read(name: String): String = Files.readString(root.resolve(name))
}
