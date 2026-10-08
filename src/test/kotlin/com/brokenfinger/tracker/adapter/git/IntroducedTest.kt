package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.aGithubShapedToken
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What a commit adds to a tree, read from git's raw diff output (#376): the blobs it leaves where something
 * else was, and the paths it adds. The answers here are shaped as `diff-index -z` and `diff-tree -r -z`
 * print them; [StagingPreviewTest] reads real ones.
 */
class IntroducedTest {
    @Test
    fun `an added or changed blob is introduced, and a removed one is not`() {
        val introduced = Introduced.ofReceived(
            change("000000", "100644", NONE, BLOB_A, 'A', "added.md") +
                change("100644", "100644", BLOB_B, BLOB_C, 'M', "changed.md") +
                change("100644", "000000", BLOB_D, NONE, 'D', "removed.md"),
            BASE,
        )

        introduced.shouldNotBeNull().listings() shouldBe listOf(listOf(BLOB_A, BLOB_C, "--not", BASE))
    }

    /** A link is stored as a blob of the path it holds, and a file that became one is a type change. */
    @Test
    fun `a link is introduced as the blob it is stored as`() {
        val introduced = Introduced.ofReceived(
            change("000000", "120000", NONE, BLOB_A, 'A', "link.md") +
                change("100644", "120000", BLOB_B, BLOB_C, 'T', "was-a-file.md") +
                change("100644", "100755", BLOB_D, BLOB_D, 'M', "made-executable.sh"),
            BASE,
        )

        introduced.shouldNotBeNull().listings() shouldBe listOf(listOf(BLOB_A, BLOB_C, BLOB_D, "--not", BASE))
    }

    /** A submodule's entry names a commit in another repository: nothing here to read. */
    @Test
    fun `a submodule's commit is not read`() {
        val introduced = Introduced.ofReceived(change("000000", "160000", NONE, BLOB_A, 'A', "vendored"), BASE)

        introduced.shouldNotBeNull().listings() shouldBe emptyList()
    }

    @Test
    fun `nothing changed is nothing introduced`() {
        Introduced.ofReceived("", BASE).shouldNotBeNull().listings() shouldBe emptyList()
    }

    /** Each list stays far inside the 32,767 characters a Windows command line holds. */
    @Test
    fun `the blobs come in bounded lists, each leaving out what the base holds`() {
        val ids = List(1_001) { "%040x".format(it + 1) }
        val answer = ids.joinToString("") { change("000000", "100644", NONE, it, 'A', "file-$it") }

        val listings = Introduced.ofReceived(answer, BASE).shouldNotBeNull().listings()

        listings.map { it.size } shouldBe listOf(502, 502, 3)
        listings.forEach { it.takeLast(2) shouldBe listOf("--not", BASE) }
        listings.flatMap { it.dropLast(2) } shouldBe ids
    }

    /** SHA-256 repositories name objects with 64 characters, and git prints them whole. */
    @Test
    fun `a repository that names objects with SHA-256 is read as well`() {
        val longer = "e".repeat(64)

        val introduced = Introduced.ofReceived(change("000000", "100644", "0".repeat(64), longer, 'A', "a.md"), BASE)

        introduced.shouldNotBeNull().listings() shouldBe listOf(listOf(longer, "--not", BASE))
    }

    @Test
    fun `an answer that is not raw diff output is no answer`() {
        val whole = change("000000", "100644", NONE, BLOB_A, 'A', "added.md")

        listOf(
            whole.dropLast(1),
            whole + "a header with no path\u0000",
            "not a header\u0000added.md\u0000",
            whole.replace(BLOB_A, BLOB_A.take(7)),
            whole.replace(":000000", "000000"),
        ).forEach { Introduced.ofReceived(it, BASE).shouldBeNull() }
    }

    // The names it adds (#376, the gap #375 found) ---------------------------------------------------

    @Test
    fun `a path it adds that holds a token is found`() {
        val introduced = Introduced.ofReceived(
            change("000000", "100644", NONE, BLOB_A, 'A', "notes/${aGithubShapedToken()}.md"),
            BASE,
        )

        introduced.shouldNotBeNull().namesSearched(nothingStored, NamesHeld.NONE) shouldBe SearchOutcome.FoundInName
    }

    /** A directory's name is in the path of everything under it, and it is matched there. */
    @Test
    fun `a directory name in a path it adds is found`() {
        val introduced = Introduced.ofReceived(
            change("000000", "100644", NONE, BLOB_A, 'A', "${aGithubShapedToken()}/notes.md"),
            BASE,
        )

        introduced.shouldNotBeNull().namesSearched(nothingStored, NamesHeld.NONE) shouldBe SearchOutcome.FoundInName
    }

    /** A path that was there before keeps the name it had: what a commit changes or removes it does not add. */
    @Test
    fun `a path it changes or removes is not one it adds`() {
        val introduced = Introduced.ofReceived(
            change("100644", "100644", BLOB_B, BLOB_C, 'M', "notes/${aGithubShapedToken()}.md") +
                change("100644", "000000", BLOB_D, NONE, 'D', "${aGithubShapedToken('B')}.md") +
                change("100644", "120000", BLOB_B, BLOB_A, 'T', "${aGithubShapedToken('C')}.md"),
            BASE,
        )

        introduced.shouldNotBeNull().namesSearched(nothingStored, NamesHeld.NONE) shouldBe SearchOutcome.Clean
    }

    /** A submodule's name is in the tree like any other. */
    @Test
    fun `the name of a submodule it adds is searched`() {
        val submodule = Introduced.ofReceived(change("000000", "160000", NONE, BLOB_A, 'A', aGithubShapedToken()), BASE)

        submodule.shouldNotBeNull().namesSearched(nothingStored, NamesHeld.NONE) shouldBe SearchOutcome.FoundInName
    }

    /**
     * Git prints a path as its bytes; the store's values are searched for as their UTF-8 bytes. A value that
     * is not ASCII is found in a path as those bytes, as `TokenPatterns` finds it in a blob.
     */
    @Test
    fun `a stored value that is not ASCII is found in a path as its bytes`() {
        val stored = StoredCredential.Patterns(listOf("schlüssel-$LONG_ENOUGH"))
        val introduced = Introduced.ofReceived(
            change("000000", "100644", NONE, BLOB_A, 'A', "notes/schlüssel-$LONG_ENOUGH.md"),
            BASE,
        )

        introduced.shouldNotBeNull().namesSearched(TokenPatterns.of(stored), NamesHeld.NONE) shouldBe
            SearchOutcome.FoundInName
    }

    @Test
    fun `paths that hold no token are not found`() {
        val introduced = Introduced.ofReceived(
            change("000000", "100644", NONE, BLOB_A, 'A', "problems/120804-두 수의 곱/Solution.java") +
                change("000000", "100644", NONE, BLOB_B, 'A', "notes/ghp_short.md"),
            BASE,
        )

        introduced.shouldNotBeNull().namesSearched(nothingStored, NamesHeld.NONE) shouldBe SearchOutcome.Clean
    }

    // Names the base already holds (#402) ---------------------------------------------------------------

    /**
     * Each part of a path it adds is a name, and a name the base already holds is not new: a file added under a
     * directory a pull named with a token adds the file's name, not the directory's.
     */
    @Test
    fun `a directory name the base already holds is not new`() {
        val added = introduced("${aGithubShapedToken()}/today.md")

        added.namesSearched(nothingStored, heldOf(aGithubShapedToken())) shouldBe SearchOutcome.Clean
    }

    @Test
    fun `a new name under a held one is found`() {
        val added = introduced("${aGithubShapedToken()}/${aGithubShapedToken('B')}.md")

        added.namesSearched(nothingStored, heldOf(aGithubShapedToken())) shouldBe SearchOutcome.FoundInName
    }

    /** Every part counts, the ones between the first and the last as well. */
    @Test
    fun `a directory between others is a name`() {
        introduced("notes/${aGithubShapedToken()}/today.md").namesSearched(nothingStored, heldOf()) shouldBe
            SearchOutcome.FoundInName
    }

    @Test
    fun `a name the base cannot say it holds is unsearched`() {
        val unknown = object : NamesHeld {
            override fun holds(name: String): Boolean? = null
        }

        introduced("notes/${aGithubShapedToken()}.md").namesSearched(nothingStored, unknown) shouldBe
            SearchOutcome.Unsearched
    }

    /** Whether a name is held costs a listing of the base, so only a name that carries a token is asked about. */
    @Test
    fun `only a name that carries a token is asked about`() {
        val asked = mutableListOf<String>()
        val counting = object : NamesHeld {
            override fun holds(name: String): Boolean = true.also { asked += name }
        }

        introduced("notes/${aGithubShapedToken()}/today.md").namesSearched(nothingStored, counting)

        asked shouldBe listOf(aGithubShapedToken())
    }

    private fun change(oldMode: String, newMode: String, old: String, new: String, status: Char, path: String) =
        ":$oldMode $newMode $old $new $status\u0000$path\u0000"

    /** What a commit that adds a file at [path], and changes nothing else, introduces. */
    private fun introduced(path: String): Introduced =
        Introduced.ofReceived(change("000000", "100644", NONE, BLOB_A, 'A', path), BASE).shouldNotBeNull()

    /** A base that holds [names], each as its bytes. */
    private fun heldOf(vararg names: String): NamesHeld = object : NamesHeld {
        private val held = names.map { String(it.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1) }.toSet()

        override fun holds(name: String): Boolean = name in held
    }

    private companion object {
        val nothingStored: TokenPatterns = TokenPatterns.of(StoredCredential.None)

        const val NONE = "0000000000000000000000000000000000000000"
        const val BLOB_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BLOB_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val BLOB_C = "cccccccccccccccccccccccccccccccccccccccc"
        const val BLOB_D = "dddddddddddddddddddddddddddddddddddddddd"

        /** The tree git has with nothing in it. */
        const val BASE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

        /** Enough that no prefix of the value is mistaken for it. */
        const val LONG_ENOUGH = "value"
    }
}
