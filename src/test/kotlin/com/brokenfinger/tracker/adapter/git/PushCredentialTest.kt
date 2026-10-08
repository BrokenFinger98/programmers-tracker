package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.madeFifo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the push credential is pointed at, and — the whole point of #267 — where it is not.
 *
 * The pointer used to be `git config credential.helper` in the record repository's own
 * `.git/config`. That file is the host's working copy too, and the path in it is the
 * container's (`/records/.ps/git-credentials`), so every host-side git operation printed
 * `fatal: unable to get credential storage lock` on a push that had already succeeded.
 */
class PushCredentialTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `points git at the store once one has been written`() {
        val file = store()

        PushCredential(root).gitConfig() shouldBe
            listOf("-c", "credential.helper=store --file=$file")
    }

    @Test
    fun `says nothing when no credential was ever stored`() {
        PushCredential(root).gitConfig().shouldBeEmpty()
    }

    /**
     * The boot order this has to survive: `CommandLineGitSync` is constructed alongside
     * [GithubRemote], which is what writes the file. Answering once at construction would leave
     * the first boot after a token was added pushing without a credential.
     */
    @Test
    fun `notices a credential written after it was constructed`() {
        val credential = PushCredential(root)
        credential.gitConfig().shouldBeEmpty()

        val file = store()

        credential.gitConfig() shouldBe listOf("-c", "credential.helper=store --file=$file")
    }

    /**
     * git runs with the record repository as its working directory, and a relative `--file`
     * would be read against whatever that happens to be. The absolute form says one thing.
     *
     * Built from the working directory rather than by relativizing a temp path: on Windows the
     * two can sit on different drives, and `relativize` throws rather than answering (the same
     * class of platform difference `ConfiguredPath` documents, and only CI can see it).
     */
    @Test
    fun `makes a relative root absolute`() {
        PushCredential(Path.of("records")).file() shouldBe
            Path.of("").toAbsolutePath().resolve("records").resolve(PushCredential.FILE)
    }

    /** `..` is kept by `toAbsolutePath` alone, and this string is read by a person when a push fails. */
    @Test
    fun `normalises the path it emits`() {
        PushCredential(root.resolve("sub").resolve("..")).file() shouldBe root.resolve(PushCredential.FILE)
    }

    /**
     * A link at the store's path was put there by someone else — a pull can deliver one — so git is
     * not pointed at it (#360). Only the regular file the server writes is the store.
     */
    @Test
    fun `points git at nothing when the store is a link`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.writeString(root.resolve("elsewhere"), "https://x-access-token:token@github.com\n")
        aLink(root.resolve(PushCredential.FILE), elsewhere)

        PushCredential(root).gitConfig().shouldBeEmpty()
    }

    // What the content gate searches for (#360) -----------------------------------------------

    /** The token wherever it sits, and the stored line as it is: a leak of the store carries both. */
    @Test
    fun `the gate searches for the token and the stored line`() {
        store(A_PUSH_TOKEN_LINE)

        patternsOf(PushCredential(root).stored()) shouldBe listOf(A_PUSH_TOKEN_LINE, A_PUSH_CREDENTIAL)
    }

    /** A secret git stores percent-encoded is searched for both ways: the store's spelling and the token's own. */
    @Test
    fun `an encoded secret is searched for as stored and as decoded`() {
        val line = "https://someone:a%2Fb@example.invalid"
        store(line)

        patternsOf(PushCredential(root).stored()) shouldBe listOf(line, "a%2Fb", "a/b")
    }

    /** It is a credential type, so it renders masked like the others (dev rules §7.2). */
    @Test
    fun `what the store holds never renders`() {
        store(A_PUSH_TOKEN_LINE)

        PushCredential(root).stored().toString() shouldNotContain A_PUSH_CREDENTIAL
    }

    @Test
    fun `an empty store is nothing to search for`() {
        store("\n\n")

        PushCredential(root).stored() shouldBe StoredCredential.None
    }

    /** A link at the store's path is not the credential, and what it leads to is not read. */
    @Test
    fun `a store that is a link cannot be searched for`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = Files.writeString(root.resolve("elsewhere"), "$A_PUSH_TOKEN_LINE\n")
        aLink(root.resolve(PushCredential.FILE), elsewhere)

        PushCredential(root).stored() shouldBe StoredCredential.Unreadable
    }

    /**
     * Only a regular file is opened. A FIFO would block every commit and push on a writer that never
     * comes; the call has to return at all. A thread of its own, because a blocked `open` cannot be
     * interrupted, and the timeout is what fails it if the check is removed.
     */
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a store that is a FIFO is not waited on`() {
        assumeTrue(canPlantLinksIn(root), "this test makes a FIFO")
        val fifo = root.resolve(PushCredential.FILE)
        Files.createDirectories(fifo.parent)
        assumeTrue(madeFifo(fifo), "no mkfifo on this machine")

        PushCredential(root).stored() shouldBe StoredCredential.Unreadable
    }

    private fun patternsOf(stored: StoredCredential): List<String> =
        (stored as StoredCredential.Patterns).asInput().lines().filter { it.isNotEmpty() }

    private fun store(line: String = "https://x-access-token:token@github.com"): Path {
        val file = root.resolve(PushCredential.FILE)
        Files.createDirectories(file.parent)
        return Files.writeString(file, "$line\n")
    }
}
