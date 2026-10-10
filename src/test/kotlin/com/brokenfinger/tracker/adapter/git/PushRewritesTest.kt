package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.adapter.git.PushDestinations.OfRemote
import com.brokenfinger.tracker.adapter.git.PushDestinations.OfUrl
import com.brokenfinger.tracker.support.git.GitWorkspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.nio.file.Path

/**
 * Whether a push goes where `ls-remote` asks what is held (#405), over **real git** and its `url.<base>` rules;
 * git's answer is altered only where git cannot be made to fail on its own. Nothing is pushed or listed: git is
 * asked what it would ask (`ls-remote --get-url`), which rules it holds, and which remotes it knows.
 */
class PushRewritesTest {
    @TempDir
    lateinit var base: Path

    private lateinit var repo: GitWorkspace

    @BeforeEach
    fun init() {
        repo = GitWorkspace(base)
    }

    @Test
    fun `a push URL no rule rewrites again agrees`() {
        rewrites().agree(OfRemote(listOf(path("A")))) shouldBe true
    }

    /** The gate critic's chain: B to A for the push URL, then A to C where `ls-remote` would ask. */
    @Test
    fun `a push URL ls-remote would rewrite again does not agree`() {
        rule(from = "A", to = "C")

        rewrites().agree(OfRemote(listOf(path("A")))) shouldBe false
    }

    @Test
    fun `one push URL of several that ls-remote would rewrite is enough to disagree`() {
        rule(from = "A", to = "C")

        rewrites().agree(OfRemote(listOf(path("D"), path("A")))) shouldBe false
    }

    @Test
    fun `a push URL ls-remote cannot resolve does not agree`() {
        val failing = altering("--get-url") { GitResult(FAILED, "", "fatal: as asked") }

        rewrites(failing).agree(OfRemote(listOf(path("A")))) shouldBe false
    }

    // A branch whose remote is a URL: ls-remote and the push each rewrite it once, alike but for pushInsteadOf

    @Test
    fun `a URL remote no pushInsteadOf rule starts agrees`() {
        rule(from = "U", to = "V")
        rule(from = "W", to = "P", kind = "pushInsteadOf")
        repo.git("remote", "add", "origin", path("O"))

        rewrites().agree(OfUrl(path("U"))) shouldBe true
    }

    @Test
    fun `a URL remote a pushInsteadOf rule starts does not agree`() {
        rule(from = "U", to = "P", kind = "pushInsteadOf")

        rewrites().agree(OfUrl(path("U"))) shouldBe false
    }

    /**
     * A rule's key holds its base, and a base can hold a space: `config --get-regexp` puts a space between key and
     * value as well (measured), so the rule is read with `--null`, where a newline parts them and no key holds one.
     */
    @Test
    fun `a pushInsteadOf rule whose base holds a space is read whole`() {
        rule(from = "U", to = "with space", kind = "pushInsteadOf")

        rewrites().agree(OfUrl(path("U"))) shouldBe false
    }

    /**
     * The gate critic's S8 (#410): a rule's value is a prefix, and an empty one starts every URL, so git pushes a URL
     * remote to the rule's base and the URL. Read as no rule, the push went where nothing was asked what it holds.
     */
    @Test
    fun `an empty pushInsteadOf value starts every URL`() {
        repo.git("config", "url.${directory("E")}.pushInsteadOf", "")

        rewrites().agree(OfUrl(path("U"))) shouldBe false
    }

    /**
     * The gate critic's S9 (#410): a rule ends what `ls-remote` would ask with a space, and trimmed, it read as the
     * push URL itself. Only the newline git ends its line with is taken off.
     */
    @Test
    fun `a push URL ls-remote would ask with a trailing space does not agree`() {
        repo.git("config", "url.${path("A")} .insteadOf", path("A"))

        rewrites().agree(OfRemote(listOf(path("A")))) shouldBe false
    }

    /**
     * The gate critic's S10b (#410): a push URL that is also a remote's name is asked by that remote's own URL. It was
     * taken for a URL, being the remote's name as well, and never asked.
     */
    @Test
    fun `a push URL that names a remote is asked where that remote fetches`() {
        repo.git("config", "remote.sub/P.git.url", path("C"))

        rewrites().agree(OfRemote(listOf("sub/P.git"))) shouldBe false
    }

    /**
     * A URL `git remote get-url` knows no remote by may still name one: `get-url` knows only those the repository's
     * own config holds, while `ls-remote` and the push take one from the global config as well, each by its own URL
     * (measured, git 2.48.1). `git remote` lists both alike, so the remote here is the repository's own.
     */
    @Test
    fun `a URL that names a remote does not agree`() {
        repo.git("config", "remote.sub/G.git.url", path("C"))

        rewrites().agree(OfUrl("sub/G.git")) shouldBe false
    }

    /** Git cannot say which remotes it knows: the URL may name one, so the push does not go. */
    @Test
    fun `a URL remote whose remotes git cannot list does not agree`() {
        val failing = altering("remote") { GitResult(FAILED, "", "fatal: as asked") }

        rewrites(failing).agree(OfUrl(path("U"))) shouldBe false
    }

    /** Git cannot say which rules it holds: one may send the push elsewhere, so the push does not go. */
    @Test
    fun `a URL remote whose rules git cannot list does not agree`() {
        val failing = altering("--get-regexp") { GitResult(FAILED, "", "fatal: bad config line 1") }

        rewrites(failing).agree(OfUrl(path("U"))) shouldBe false
    }

    /** A listing cut off by its timeout says nothing either, though git did not exit with an error. */
    @Test
    fun `a URL remote whose rules were not listed in time does not agree`() {
        val timedOut = altering("--get-regexp") { GitResult(TIMED_OUT, "", "") }

        rewrites(timedOut).agree(OfUrl(path("U"))) shouldBe false
    }

    private fun rewrites(calls: GitCalls = calls()): PushRewrites = PushRewrites(calls)

    /** A repository's path under [name]'s directory beside the workspace; nothing need be there. */
    private fun path(name: String): String = "${directory(name)}/repo.git"

    /** `url.<[to]>/.<[kind]> = <[from]>/`: what is addressed under [from]'s directory goes to [to]'s instead. */
    private fun rule(from: String, to: String, kind: String = "insteadOf") {
        repo.git("config", "url.${directory(to)}/.$kind", "${directory(from)}/")
    }

    /**
     * [name]'s directory beside the workspace, with forward slashes, as git writes a URL on every system. A rule is a
     * plain prefix of the URL as written (`starts_with` in git's `remote.c`): with Windows' backslashes in the path
     * and a slash after it, `C:\…\A/` never starts `C:\…\A\repo.git`, so on Windows CI no rule ever rewrote, and the
     * tests that wait for a rewrite failed while nothing was rewritten (#410).
     */
    private fun directory(name: String): String = base.resolve(name).toString().replace(File.separatorChar, '/')

    /** Git's real answers, each to a call whose arguments hold [argument] replaced by [change]'s. */
    private fun altering(argument: String, change: (GitResult) -> GitResult): GitCalls =
        calls { args, answer -> answer.takeUnless { argument in args } ?: change(answer) }

    private fun calls(answered: (List<String>, GitResult) -> GitResult = { _, answer -> answer }): GitCalls =
        object : GitCalls {
            private val real = ProcessCalls(GitProcess(repo.root)) { listOf("git") + it }

            override fun answer(args: List<String>, input: String?): GitResult =
                answered(args, real.answer(args, input))

            override fun <T : Any> streamed(args: List<String>, input: String?, read: (InputStream) -> T): T? =
                real.streamed(args, input, read)
        }

    private companion object {
        const val FAILED = 128

        /** What [GitProcess] answers for a call cut off by its timeout. */
        const val TIMED_OUT = -1
    }
}
