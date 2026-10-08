package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a push would not send again, as its destinations themselves say (#376): real repositories and
 * real bare remotes, asked with `ls-remote`; git's answer is altered only where git cannot be made to give
 * the answer under test. Nothing `ls-remote` prints is ever printed here.
 */
class RemoteTipsTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
        committed("README.md", "# records\n")
    }

    @Test
    fun `an empty remote holds nothing`() {
        tipsHeldBy(bare("empty.git")).shouldBeEmpty()
    }

    @Test
    fun `a remote holds the tips it advertises that this repository has`() {
        val remote = bare("remote.git")
        repo.git("push", "--quiet", remote.toString(), "HEAD:refs/heads/main")

        tipsHeldBy(remote) shouldContainExactlyInAnyOrder listOf(head())
    }

    /** A tip only another clone made cannot stand in a range here, and a push from here does not send it. */
    @Test
    fun `a tip this repository lacks is left out`() {
        val remote = bare("remote.git")
        repo.git("push", "--quiet", remote.toString(), "HEAD:refs/heads/main")
        val mine = head()
        aCommitOnlyElsewhereOn(remote, branch = "elsewhere")

        tipsHeldBy(remote) shouldContainExactlyInAnyOrder listOf(mine)
    }

    /** Several push URLs: a tip counts only where every one of them holds it, so nothing one lacks goes unread. */
    @Test
    fun `a tip counts only when every destination holds it`() {
        val first = bare("first.git")
        val second = bare("second.git")
        repo.git("push", "--quiet", first.toString(), "HEAD:refs/heads/main")
        val older = head()
        committed("notes/a.md", "a note\n")
        repo.git("push", "--quiet", first.toString(), "HEAD:refs/heads/next")
        repo.git("push", "--quiet", second.toString(), "$older:refs/heads/main")

        RemoteTips(calls()).heldByAll(listOf(first.toString(), second.toString())) shouldContainExactlyInAnyOrder
            listOf(older)
    }

    @Test
    fun `a destination that cannot be asked leaves what it holds unknown`() {
        RemoteTips(calls()).heldByAll(listOf(base.resolve("nowhere.git").toString())).shouldBeNull()
    }

    @Test
    fun `one destination that cannot be asked leaves the answer unknown for all`() {
        val remote = bare("remote.git")
        repo.git("push", "--quiet", remote.toString(), "HEAD:refs/heads/main")

        RemoteTips(calls()).heldByAll(listOf(remote.toString(), base.resolve("nowhere.git").toString())).shouldBeNull()
    }

    /** No destination is no answer, never "nothing to exclude". */
    @Test
    fun `no destination at all leaves the answer unknown`() {
        RemoteTips(calls()).heldByAll(emptyList()).shouldBeNull()
    }

    /** A line that is not `<id><TAB><ref>` is an answer git did not give. */
    @Test
    fun `an answer that is not a list of refs leaves it unknown`() {
        val remote = bare("remote.git")
        repo.git("push", "--quiet", remote.toString(), "HEAD:refs/heads/main")
        val altered = altering(LIST) { it.copy(stdout = it.stdout + "not a ref\n") }

        RemoteTips(altered).heldByAll(listOf(remote.toString())).shouldBeNull()
    }

    /** Which tips are held is asked of every tip at once; an answer short of one is not an answer. */
    @Test
    fun `a description that does not cover every tip leaves it unknown`() {
        val remote = bare("remote.git")
        repo.git("push", "--quiet", remote.toString(), "HEAD:refs/heads/main")

        RemoteTips(altering(DESCRIBE) { it.copy(stdout = "") }).heldByAll(listOf(remote.toString())).shouldBeNull()
    }

    @Test
    fun `a description that fails leaves it unknown`() {
        val remote = bare("remote.git")
        repo.git("push", "--quiet", remote.toString(), "HEAD:refs/heads/main")

        RemoteTips(altering(DESCRIBE) { it.copy(code = 128) }).heldByAll(listOf(remote.toString())).shouldBeNull()
    }

    private fun tipsHeldBy(remote: Path): Set<String>? = RemoteTips(calls()).heldByAll(listOf(remote.toString()))

    /** Git's real answers, with the answer to every call whose arguments hold [argument] altered by [change]. */
    private fun altering(argument: String, change: (GitResult) -> GitResult): GitCalls =
        calls { args, answer -> answer.takeUnless { argument in args } ?: change(answer) }

    /** Git's real answers in the workspace, each one read whole first passed through [answered]. */
    private fun calls(answered: (List<String>, GitResult) -> GitResult = { _, answer -> answer }): GitCalls =
        object : GitCalls {
            private val real = ProcessCalls(GitProcess(repo.root)) { listOf("git") + it }

            override fun answer(args: List<String>, input: String?): GitResult =
                answered(args, real.answer(args, input))

            override fun <T : Any> streamed(args: List<String>, input: String?, read: (InputStream) -> T): T? =
                real.streamed(args, input, read)
        }

    private fun bare(name: String): Path = base.resolve(name).also {
        repo.git("init", "--quiet", "--bare", it.toString())
    }

    private fun head(): String = repo.git("rev-parse", "HEAD").trim()

    private fun committed(relative: String, content: String) {
        val file = repo.root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        repo.git("add", "--", relative)
        repo.git("commit", "--quiet", "--message", "add $relative")
    }

    /** A commit another clone of [remote] made and pushed to [branch]; this repository never sees it. */
    private fun aCommitOnlyElsewhereOn(remote: Path, branch: String) {
        val other = GitWorkspace(base.resolve("other"))
        other.git("pull", "--quiet", remote.toString(), "main")
        other.write("elsewhere.md", "someone else's work\n")
        other.git("add", "--all")
        other.git("commit", "--quiet", "--message", "elsewhere")
        other.git("push", "--quiet", remote.toString(), "HEAD:refs/heads/$branch")
    }

    private companion object {
        const val LIST = "ls-remote"
        const val DESCRIBE = "--batch-check"
    }
}
