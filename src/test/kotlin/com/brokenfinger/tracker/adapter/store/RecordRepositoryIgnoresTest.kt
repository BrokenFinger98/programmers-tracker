package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The record repository must ignore the state directory, because the state moved inside it
 * (#126) and reconciliation is `git add --all`.
 *
 * `.ps/raw/recorded/` holds a **copy** of every `attempts/00N.raw.jsonl` — [RecordWriter]
 * copies the frames in and retires the source. A repository that does not ignore it commits
 * the whole capture history twice and pushes it. Repositories created before #122 carry a
 * `.gitignore` that names `.ps/session` and `.ps/catalog.json` one at a time and ignores none
 * of this, and there is no upgrade path for a file the user owns other than adding the line.
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

        read(".gitignore") shouldBe "# mine\n.ps\n"
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
