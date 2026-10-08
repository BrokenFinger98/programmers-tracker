package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.adapter.store.TrackedHistory
import com.brokenfinger.tracker.adapter.store.TrackedHistory.Known
import com.brokenfinger.tracker.support.fixtures.A_LONG_S_STATE_DIRECTORY
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether git tracks anything under the state directory (#360). A pull can deliver a tracked file
 * there — a credential store holding one letter, a link — and a path git tracks is one any
 * `commit -a` publishes once the server writes to it. Real git, because what git lists is the oracle.
 */
class TrackedStateEntriesTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
    }

    @Test
    fun `nothing tracked under the state directory is answered no`() {
        repo.write("notes.md", "a note\n")
        repo.git("add", "notes.md")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe false
    }

    @Test
    fun `a file tracked under it is answered yes`() {
        repo.write(PushCredential.FILE, "e\n")
        repo.git("add", "--force", PushCredential.FILE)

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    @Test
    fun `the entry itself, tracked as a link, is answered yes`() {
        assumeTrue(canPlantLinksIn(repo.root), "this test makes symbolic links")
        aLink(repo.root.resolve(".ps"), Files.createDirectories(repo.root.resolve("problems/zz")))
        repo.git("add", ".ps")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    /** Asked in any case: `.PS/x` in the index is the state directory on a volume that folds case. */
    @Test
    fun `an entry in another case is answered yes`() {
        repo.write("decoy", "decoy\n")
        val blob = repo.git("hash-object", "-w", "--no-filters", "decoy").trim()
        repo.git("update-index", "--add", "--cacheinfo", "100644,$blob,.PS/x")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    /**
     * `.pſ` — `.p` and U+017F — folds to `.ps` on APFS, so `.pſ/x` in the index is a file inside the
     * real state directory, and an ASCII-only `icase` pathspec never named it (U1, the review of
     * ea1357c). Asked of the index itself, so it holds where nothing folds as well.
     */
    @Test
    fun `an entry under a Unicode case fold of the name is answered yes`() {
        repo.write("decoy", "decoy\n")
        val blob = repo.git("hash-object", "-w", "--no-filters", "decoy").trim()
        repo.git("update-index", "--add", "--cacheinfo", "100644,$blob,$A_LONG_S_STATE_DIRECTORY/x")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    /**
     * Whatever the name, an entry whose first segment is the state directory on disk is under it — a
     * fold no case rule knows, a short name, a link. A tracked link to `.ps` shows it on any filesystem.
     */
    @Test
    fun `an entry the filesystem resolves to the state directory is answered yes`() {
        assumeTrue(canPlantLinksIn(repo.root), "this test makes symbolic links")
        val state = Files.createDirectories(repo.root.resolve(".ps"))
        aLink(repo.root.resolve("elsewhere"), state)
        repo.git("add", "elsewhere")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe true
    }

    @Test
    fun `a name that only starts like it is answered no`() {
        repo.write(".ps2/notes.md", "not state\n")
        repo.git("add", ".ps2/notes.md")

        TrackedStateEntries(repo.root).tracksAnything() shouldBe false
    }

    /**
     * The question reads the repository's own index whatever the server's environment names: pointed
     * at an empty one, it answered that nothing was tracked (#360, the review's ENV).
     */
    @Test
    fun `a tracked state file is found whatever index the environment names`() {
        repo.write(PushCredential.FILE, "e\n")
        repo.git("add", "--force", PushCredential.FILE)
        val environment = System.getenv() + mapOf("GIT_INDEX_FILE" to base.resolve("empty-index").toString())

        TrackedStateEntries(repo.root, environment).tracksAnything() shouldBe true
    }

    @Test
    fun `a directory git cannot answer for is answered neither`() {
        val elsewhere = Files.createDirectories(base.resolve("not-a-repository"))

        TrackedStateEntries(elsewhere).tracksAnything().shouldBeNull()
    }

    // What git has ever tracked below the state directory (#377) ----------------------------------

    /**
     * A pull can deliver a raw session under `.ps`, and untracking it leaves the file where git put it (the
     * review of PR #395). Git's history still names it, under every spelling that lands in the state directory,
     * each path relative to it and spelled as committed.
     */
    @Test
    fun `paths git has ever tracked below the state directory are answered after they are untracked`() {
        val paths = listOf(".ps/raw/a.jsonl", ".PS/raw/b.jsonl", "$A_LONG_S_STATE_DIRECTORY/RAW/c.jsonl")
        tracked(paths + "problems/1-x/README.md" + ".ps2/raw/d.jsonl")
        repo.git("commit", "--message", "as a pull delivers it")
        repo.git("rm", "--cached", "--quiet", "--", *paths.toTypedArray())
        repo.git("commit", "--message", "untracked")

        TrackedStateEntries(repo.root).pathsEverTracked() shouldBe
            Known(setOf("raw/a.jsonl", "raw/b.jsonl", "RAW/c.jsonl"))
    }

    @Test
    fun `a repository with no commit has tracked nothing below the state directory`() {
        TrackedStateEntries(repo.root).pathsEverTracked() shouldBe Known(emptySet())
    }

    @Test
    fun `history git cannot read is answered neither`() {
        val elsewhere = Files.createDirectories(base.resolve("not-a-repository"))

        TrackedStateEntries(elsewhere).pathsEverTracked().shouldBeInstanceOf<TrackedHistory.Unanswered>()
    }

    /**
     * A failed history held every session without saying why (the review of PR #395): git's own words say it. Only
     * their start is asserted, the part every git version has said; what follows differs between them.
     */
    @Test
    fun `a history git cannot read is answered with git's reason`() {
        val elsewhere = Files.createDirectories(base.resolve("not-a-repository"))

        val reason = TrackedStateEntries(elsewhere).pathsEverTracked().shouldBeInstanceOf<TrackedHistory.Unanswered>()
            .reason

        reason shouldStartWith "git log exited 128: fatal: not a git repository"
    }

    /** A git that cannot be started — no such directory, or no git at all — is answered with why, never thrown. */
    @Test
    fun `a history git could not be started for is answered with why`() {
        TrackedStateEntries(base.resolve("no-such-directory")).pathsEverTracked() shouldBe
            TrackedHistory.Unanswered("IOException")
    }

    // The reason itself, built from git's result alone (PR #401) --------------------------------

    /**
     * Git's own line is cut at its bound, so a path it names cannot make the reason as long as it likes. Pinned on
     * a result made here: a real git named a missing git directory in full in 2.48.1 and as `(null)` on CI's
     * gits, so a real one tests the bound only on some machines.
     */
    @Test
    fun `git's own line in a reason is cut at its bound`() {
        val line = "fatal: " + "x".repeat(300)

        TrackedStateEntries.reasonOf(GitResult(128, "", "$line\nthe line after it\n")) shouldBe
            "git log exited 128: " + line.take(TrackedStateEntries.REASON_LENGTH)
    }

    @Test
    fun `the first line git said something on is its reason`() {
        TrackedStateEntries.reasonOf(GitResult(128, "", "\n   \n  fatal: what went wrong  \nmore\n")) shouldBe
            "git log exited 128: fatal: what went wrong"
    }

    @Test
    fun `a git that said nothing is answered with how it ended`() {
        TrackedStateEntries.reasonOf(GitResult(1, "", "")) shouldBe "git log exited 1"
    }

    @Test
    fun `a git that did not finish is answered with its timeout`() {
        TrackedStateEntries.reasonOf(GitResult(GitProcess.TIMED_OUT, "", "")) shouldBe
            "git log did not finish within ${GitProcess.TIMEOUT.seconds} s"
    }

    /**
     * Skipping the delete, the owner untracked a pulled session, reset past the commit that delivered it and
     * force-pushed: no ref reached that commit any more, only the reflog, and the session was replayed (the review
     * of PR #395, measured on real git). The reflogs are read too.
     */
    @Test
    fun `a path a reset and a force-push left to the reflog alone is answered`() {
        repo.withRemote()
        val before = repo.git("rev-parse", "HEAD").trim()
        delivered(".ps/raw/s.jsonl")
        repo.git("push", "--quiet", "origin", "main")
        untrackedWithoutDeleting()
        repo.git("reset", "--quiet", "--hard", before)
        repo.git("push", "--quiet", "--force", "origin", "main")

        TrackedStateEntries(repo.root).pathsEverTracked() shouldBe Known(setOf("raw/s.jsonl"))
    }

    /** The attacker force-pushed the delivery away, and the owner's plain `pull --rebase` dropped it from every ref. */
    @Test
    fun `a path a force-push and a pull with rebase left to the reflog alone is answered`() {
        val remote = repo.withRemote()
        val before = repo.git("rev-parse", "HEAD").trim()
        val attacker = aCloneOf(remote, "attacker")
        delivered(".ps/raw/s.jsonl", at = attacker)
        repo.git("push", "--quiet", "origin", "main", at = attacker)
        repo.git("pull", "--quiet", "--rebase")
        untrackedWithoutDeleting()
        repo.git("reset", "--quiet", "--hard", before, at = attacker)
        repo.git("push", "--quiet", "--force", "origin", "main", at = attacker)

        repo.git("pull", "--quiet", "--rebase")

        repo.git("log", "--all", "--format=%s").trim() shouldBe "init"
        TrackedStateEntries(repo.root).pathsEverTracked() shouldBe Known(setOf("raw/s.jsonl"))
    }

    private fun delivered(path: String, at: Path = repo.root) {
        val file = at.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}\n")
        repo.git("add", "--force", path, at = at)
        repo.git("commit", "--quiet", "--message", "as a pull delivers it", at = at)
    }

    private fun untrackedWithoutDeleting() {
        repo.git("rm", "-r", "--cached", "--quiet", ".ps")
        repo.git("commit", "--quiet", "--message", "untracked, the file left on disk")
    }

    private fun aCloneOf(remote: Path, name: String): Path {
        val clone = base.resolve(name)
        repo.git("clone", "--quiet", remote.toString(), clone.toString(), at = base)
        repo.git("config", "user.email", "test@example.invalid", at = clone)
        repo.git("config", "user.name", "Tracker Test", at = clone)
        repo.git("config", "commit.gpgsign", "false", at = clone)
        return clone
    }

    /** A fetch brings branches nobody checks out, and a pull can be undone while its branch stays. */
    @Test
    fun `a path on a branch never checked out is answered`() {
        repo.write("notes.md", "a note\n")
        repo.git("add", "notes.md")
        repo.git("commit", "--message", "base")
        repo.git("checkout", "--quiet", "-b", "upstream")
        tracked(listOf(".ps/raw/o.jsonl"))
        repo.git("commit", "--message", "upstream only")
        repo.git("checkout", "--quiet", "main")

        TrackedStateEntries(repo.root).pathsEverTracked() shouldBe Known(setOf("raw/o.jsonl"))
    }

    /**
     * A path the first commit added has no parent to differ from, and `log.showRoot` off hides that commit's
     * paths. The owner's own git configuration reaches the tracker's git, so `--root` names them regardless.
     */
    @Test
    fun `a path the first commit added is answered, whatever the log configuration says`() {
        repo.git("config", "log.showRoot", "false")
        tracked(listOf(".ps/raw/r.jsonl"))
        repo.git("commit", "--message", "the first commit")

        TrackedStateEntries(repo.root).pathsEverTracked() shouldBe Known(setOf("raw/r.jsonl"))
    }

    /** A path a merge alone added is in neither parent, so only the merge compared with each parent names it. */
    @Test
    fun `a path a merge alone added is answered`() {
        repo.write("notes.md", "a note\n")
        repo.git("add", "notes.md")
        repo.git("commit", "--message", "base")
        repo.git("checkout", "--quiet", "-b", "side")
        repo.write("side.md", "side\n")
        repo.git("add", "side.md")
        repo.git("commit", "--message", "side")
        repo.git("checkout", "--quiet", "main")
        repo.write("main.md", "main\n")
        repo.git("add", "main.md")
        repo.git("commit", "--message", "main")
        repo.git("merge", "--no-ff", "--no-commit", "--quiet", "side")
        tracked(listOf(".ps/raw/m.jsonl"))
        repo.git("commit", "--message", "a merge that adds a session")

        TrackedStateEntries(repo.root).pathsEverTracked() shouldBe Known(setOf("raw/m.jsonl"))
    }

    // The way out the refusal names, under every spelling (#377) ------------------------------------

    /**
     * `git ls-files .ps` listed none of these and `git rm -r --cached .ps` untracked none, so the refusal stood
     * after the owner followed it (the review of PR #395, measured on APFS). The commands the TRACKED reason
     * gives, run here exactly as the owner reads them, list each spelling and leave git tracking nothing there.
     */
    @ParameterizedTest
    @ValueSource(strings = [".ps/raw", ".PS/raw", ".Ps/raw", ".pS/raw", ".pſ/raw", ".Pſ/raw", ".PS/RAW", ".pſ/RAW"])
    fun `the TRACKED reason lists and untracks what git tracks under any spelling of the state directory`(
        directory: String,
    ) {
        tracked(listOf("$directory/x.jsonl"))
        repo.git("commit", "--message", "as a pull delivers it")
        val reason = StateDirectory.Refusal.TRACKED.reason

        ran(commandIn(reason, "git ls-files")).trim() shouldBe "$directory/x.jsonl"
        ran(commandIn(reason, "git rm"))

        TrackedStateEntries(repo.root).tracksAnything() shouldBe false
    }

    // A command the owner is told to run, as backquoted in [reason].
    private fun commandIn(reason: String, starting: String): String =
        Regex("`([^`]+)`").findAll(reason).map { it.groupValues[1] }.first { it.startsWith(starting) }

    // Run as a shell would split it: single quotes keep a word whole. Never through a shell, which Windows lacks.
    private fun ran(command: String): String {
        val words = Regex("'([^']*)'|(\\S+)").findAll(command).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        return repo.git(*words.drop(1).toList().toTypedArray())
    }

    /** [paths] in the index as git would hold them after a checkout, whatever this filesystem folds. */
    private fun tracked(paths: List<String>) {
        repo.write("decoy", "decoy\n")
        val blob = repo.git("hash-object", "-w", "--no-filters", "decoy").trim()
        paths.forEach { repo.git("update-index", "--add", "--cacheinfo", "100644,$blob,$it") }
    }
}
