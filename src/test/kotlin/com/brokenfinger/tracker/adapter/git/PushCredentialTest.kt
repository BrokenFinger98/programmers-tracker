package com.brokenfinger.tracker.adapter.git

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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

    private fun store(): Path {
        val file = root.resolve(PushCredential.FILE)
        Files.createDirectories(file.parent)
        return Files.writeString(file, "https://x-access-token:token@github.com\n")
    }
}
