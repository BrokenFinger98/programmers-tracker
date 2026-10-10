package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.keepsPosixPermissions
import com.brokenfinger.tracker.support.git.GitWorkspace
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

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
     * The temporary file a crash leaves beside a file the tracker writes whole (#386). The tracker's own commits leave
     * it out by pathspec; this rule keeps it out of every other `git add` too, such as an Obsidian Git backup. Only a
     * hidden name with the tracker's ending: a visible file of the owner's that ends the same way is theirs. Asked of
     * git itself.
     */
    @Test
    fun `ignores the temporary file a crash leaves, and no visible file of the owner's`(@TempDir base: Path) {
        val repo = GitWorkspace(base)
        RecordRepositoryIgnores(repo.root).ensure()
        val left = "problems/1-x/.README.md.4242${FileReplacement.TEMP_SUFFIX}"
        repo.write(left, "half\n")
        repo.write("notes/report${FileReplacement.TEMP_SUFFIX}", "mine\n")

        repo.statusOf(left) shouldBe ""
        repo.statusOf("notes") shouldBe "?? notes/"
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

    // A .gitignore that is not a regular file (#360) -------------------------------------------

    /**
     * Git stores links, so a `.gitignore` can arrive as one. Read through and written back, the rules
     * landed in whatever it pointed at — here a file outside the repository — while git, which never
     * follows a linked `.gitignore`, ignored nothing. So it is neither read nor written, and said.
     */
    @Test
    fun `a gitignore that is a link is neither read nor written through`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val records = Files.createDirectories(root.resolve("records"))
        val outside = Files.writeString(root.resolve("someone-elses-ignore"), OUTSIDE)
        aLink(records.resolve(".gitignore"), outside)

        val warnings = warningsWhile(RecordRepositoryIgnores::class) { RecordRepositoryIgnores(records).ensure() }

        Files.readAllBytes(outside) shouldBe OUTSIDE.toByteArray()
        Files.isSymbolicLink(records.resolve(".gitignore")) shouldBe true
        warnings.single() shouldContain "is not a regular file"
        warnings.single() shouldNotContain "someone-elses-ignore"
    }

    /** A link to nowhere looks absent unless asked without following it, and writing through it created a file. */
    @Test
    fun `a dangling gitignore link creates nothing where it points`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val records = Files.createDirectories(root.resolve("records"))
        val target = root.resolve("not-there")
        aLink(records.resolve(".gitignore"), target)

        RecordRepositoryIgnores(records).ensure()

        Files.exists(target, LinkOption.NOFOLLOW_LINKS) shouldBe false
    }

    /**
     * Only an absent file is an empty one. A `.gitignore` that is there and is not UTF-8 read as empty,
     * and the rules were written over everything it held.
     */
    @Test
    fun `a gitignore that cannot be read as text is left exactly as it was`() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()) + "# mine\n".toByteArray()
        Files.write(root.resolve(".gitignore"), bytes)

        val warnings = warningsWhile(RecordRepositoryIgnores::class) { RecordRepositoryIgnores(root).ensure() }

        Files.readAllBytes(root.resolve(".gitignore")) shouldBe bytes
        warnings.single() shouldContain "Could not ensure"
    }

    /**
     * An owner who made `.gitignore` read-only meant nothing to rewrite it, and a replace needs only the
     * directory, so it went through anyway (#360). It is left as it is, and the missing rules are said.
     */
    @Test
    fun `a read-only gitignore is left as it is, and said`() {
        assumeTrue(keepsPosixPermissions(root), "this test sets POSIX permissions")
        val file = write(".gitignore", "# mine, read-only on purpose\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
        assumeTrue(!Files.isWritable(file), "a superuser writes it anyway")

        val warnings = warningsWhile(RecordRepositoryIgnores::class) { RecordRepositoryIgnores(root).ensure() }

        read(".gitignore") shouldBe "# mine, read-only on purpose\n"
        warnings.single() shouldContain "read-only"
    }

    /**
     * Replaced, never written into: that is what stops a link that appears between the check and
     * the write from being written through. A hard link shows the difference without the race — it
     * is a regular file, and written into, it changed the file it shares with.
     */
    @Test
    fun `a regular gitignore gains its rules by replacement, never by writing into it`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a hard link")
        val records = Files.createDirectories(root.resolve("records"))
        val shared = Files.writeString(root.resolve("shared-ignore"), OUTSIDE)
        Files.createLink(records.resolve(".gitignore"), shared)

        RecordRepositoryIgnores(records).ensure()

        Files.readAllBytes(shared) shouldBe OUTSIDE.toByteArray()
        Files.readString(records.resolve(".gitignore")).shouldContain("\n.ps/\n")
    }

    /**
     * The rules are added by replacing the file rather than writing into it, which is what stops a
     * link that appears in between from being written through. The replacement must not narrow
     * the permissions the owner's file had.
     */
    @Test
    fun `a gitignore that gains a rule keeps its permissions`() {
        assumeTrue(keepsPosixPermissions(root), "this test reads POSIX permissions")
        val file = write(".gitignore", "# mine\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(GROUP_WRITABLE))

        RecordRepositoryIgnores(root).ensure()

        read(".gitignore").shouldContain("\n.ps/\n")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe GROUP_WRITABLE
    }

    private fun write(name: String, text: String) = Files.writeString(root.resolve(name), text)

    private fun read(name: String): String = Files.readString(root.resolve(name))

    private companion object {
        const val OUTSIDE = "# a file outside the records repository\n"

        /** Neither what a temporary file starts as nor the usual default, so keeping it proves something. */
        const val GROUP_WRITABLE = "rw-rw-r--"
    }
}
