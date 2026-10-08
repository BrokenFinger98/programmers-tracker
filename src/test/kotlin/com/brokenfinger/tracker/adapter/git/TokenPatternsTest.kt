package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.fixtures.A_PUSH_CREDENTIAL
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aFineGrainedShapedToken
import com.brokenfinger.tracker.support.fixtures.aGithubShapedToken
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the content gate looks for in a window of bytes (#360, #373), and that it finds exactly what
 * `git grep` finds in the C locale, which every git call of the tracker runs in (#372) — and, in UTF-16
 * text, what `git grep` finds in no locale. Token-shaped strings are built at runtime, never written out.
 */
class TokenPatternsTest {
    @TempDir
    lateinit var base: Path

    private val nothingStored = TokenPatterns.of(StoredCredential.None)

    // The shapes, from their shortest form --------------------------------------------------------

    @Test
    fun `a classic token is found from its shortest form`() {
        nothingStored.foundIn(aGithubShapedToken().take(CLASSIC_MINIMUM)) shouldBe true
        nothingStored.foundIn(aGithubShapedToken().take(CLASSIC_MINIMUM - 1)) shouldBe false
    }

    @Test
    fun `every classic prefix is a token, and no other is`() {
        "pousr".forEach { nothingStored.foundIn(classicWith(it)) shouldBe true }
        "axPU".forEach { nothingStored.foundIn(classicWith(it)) shouldBe false }
    }

    /** The longest of the shortest matches is what a window must repeat, so it is pinned here. */
    @Test
    fun `a fine-grained token is found from its shortest form`() {
        val shortest = aFineGrainedShapedToken().take(TokenPatterns.LARGEST_MINIMUM_MATCH)

        nothingStored.foundIn(shortest) shouldBe true
        nothingStored.foundIn(shortest.dropLast(1)) shouldBe false
    }

    // What the store holds ---------------------------------------------------------------------------

    @Test
    fun `the stored line and the token in it are found, and nothing else is`() {
        val stored = patternsOf(A_PUSH_TOKEN_LINE)

        stored.foundIn("a note: $A_PUSH_TOKEN_LINE") shouldBe true
        stored.foundIn("a note: $A_PUSH_CREDENTIAL") shouldBe true
        stored.foundIn("a note: ${A_PUSH_CREDENTIAL.dropLast(1)}") shouldBe false
        nothingStored.foundIn("a note: $A_PUSH_CREDENTIAL") shouldBe false
    }

    /** `git grep -F -f -` is fed the store's UTF-8 bytes, so a value is found as those bytes and no others. */
    @Test
    fun `a stored value is found as the UTF-8 bytes git is fed`() {
        val stored = patternsOf(STORED_WITH_UMLAUTS)

        stored.foundIn(windowOf("pässwörd".toByteArray(Charsets.UTF_8))) shouldBe true
        stored.foundIn(windowOf("pässwörd".toByteArray(Charsets.ISO_8859_1))) shouldBe false
    }

    // How much a window repeats --------------------------------------------------------------------------

    @Test
    fun `a window repeats one byte fewer than the longest shortest match, read as UTF-16`() {
        nothingStored.overlap shouldBe TokenPatterns.LARGEST_MINIMUM_MATCH * 2 - 1
    }

    @Test
    fun `a stored value longer than every shape sets what a window repeats`() {
        val long = "https://x-access-token:${"n".repeat(LONGER_THAN_A_SHAPE)}@github.com"

        patternsOf(long).overlap shouldBe long.length * 2 - 1
    }

    // UTF-16, where git grep finds nothing (#372's review) -----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["UTF-16LE", "UTF-16BE", "UTF-16"])
    fun `a token in UTF-16 text is found, in either byte order and with a byte order mark`(charset: String) {
        val text = "note ${aGithubShapedToken()}\n".toByteArray(Charset.forName(charset))

        nothingStored.foundIn(windowOf(text)) shouldBe true
    }

    /** Text that starts at an odd offset — after one byte of something else — is read from its second byte. */
    @ParameterizedTest
    @ValueSource(strings = ["UTF-16LE", "UTF-16BE"])
    fun `a token in UTF-16 text an odd number of bytes in is found`(charset: String) {
        val text = byteArrayOf(1) + aFineGrainedShapedToken().toByteArray(Charset.forName(charset))

        nothingStored.foundIn(windowOf(text)) shouldBe true
    }

    @Test
    fun `a stored value in UTF-16 text is found`() {
        val text = "my token is $A_PUSH_CREDENTIAL".toByteArray(Charsets.UTF_16LE)

        patternsOf(A_PUSH_TOKEN_LINE).foundIn(windowOf(text)) shouldBe true
    }

    @Test
    fun `UTF-16 that only resembles a token is not one`() {
        val text = "ghp_short, ${classicWith('x')}".toByteArray(Charsets.UTF_16LE)

        nothingStored.foundIn(windowOf(text)) shouldBe false
    }

    // Parity with git grep, in the C locale ------------------------------------------------------------

    /**
     * The shapes accept exactly what `git grep -E` accepts, probe for probe, as files git reads as bytes:
     * lengths either side of each bound, every prefix, a NUL, a newline or a non-ASCII byte inside the run
     * and beside it. Beside an invalid UTF-8 sequence is where `git grep` in a UTF-8 locale on macOS found
     * nothing (measured on 2.48.1), so the C locale is named here as the tracker names it.
     */
    @Test
    fun `the shapes find exactly what git grep -E finds`() {
        val probes = shapeProbes()
        val byGit = foundByGit(probes, listOf("-E") + TokenPatterns.SHAPES.flatMap { listOf("-e", it) })

        foundHere(probes, nothingStored) shouldContainExactly byGit
    }

    /**
     * The stored values, as `git grep -F -f -` reads them: a line each, and a CR before the newline is part
     * of the line ending, not of the value — the secret of [STORED_ENDING_IN_CR] is searched for without it.
     */
    @Test
    fun `the stored values find exactly what git grep -F finds`() {
        val lines = listOf(A_PUSH_TOKEN_LINE, STORED_WITH_UMLAUTS, STORED_ENDING_IN_CR)
        val stored = StoredCredential.of(lines.joinToString("\n", postfix = "\n")) as StoredCredential.Patterns
        val probes = storedProbes()
        val byGit = foundByGit(probes, listOf("-F", "-f", "-"), stored.asInput())

        foundHere(probes, TokenPatterns.of(stored)) shouldContainExactly byGit
    }

    private fun shapeProbes(): Map<String, ByteArray> {
        val classic = aGithubShapedToken().take(CLASSIC_MINIMUM)
        val fine = aFineGrainedShapedToken().take(TokenPatterns.LARGEST_MINIMUM_MATCH)
        val beside = mapOf("latin1" to "e9", "overlong" to "c0af", "continuation" to "80", "ff" to "ff")
        return mapOf(
            "classic" to bytes(classic),
            "classic-short" to bytes(classic.dropLast(1)),
            "fine" to bytes(fine),
            "fine-short" to bytes(fine.dropLast(1)),
            "underscores" to bytes("ghs_" + "A_".repeat(18)),
            "upper" to bytes(classic.uppercase()),
            "nul-inside" to bytes(classic.take(20)) + byteArrayOf(0) + bytes(classic.drop(20)),
            "nul-beside" to byteArrayOf(0) + bytes(classic) + byteArrayOf(0),
            "newline-inside" to bytes(classic.take(20) + "\n" + classic.drop(20)),
            "cr-inside" to bytes(classic.take(20) + "\r" + classic.drop(20)),
            "utf8-inside" to bytes(classic.take(20) + "é" + classic.drop(20)),
            "dash-inside" to bytes(fine.take(40) + "-" + fine.drop(40)),
        ) + beside.flatMap { (name, hex) ->
            listOf("$name-before" to hexBytes(hex) + bytes(classic), "$name-after" to bytes(fine) + hexBytes(hex))
        }
    }

    private fun storedProbes(): Map<String, ByteArray> = mapOf(
        "line" to bytes("see $A_PUSH_TOKEN_LINE"),
        "credential" to bytes("token=$A_PUSH_CREDENTIAL;"),
        "credential-short" to bytes(A_PUSH_CREDENTIAL.dropLast(1)),
        "umlauts-utf8" to "pässwörd".toByteArray(Charsets.UTF_8),
        "umlauts-latin1" to "pässwörd".toByteArray(Charsets.ISO_8859_1),
        "nul-beside" to byteArrayOf(0) + bytes(A_PUSH_CREDENTIAL) + byteArrayOf(0),
        "cr-ended" to bytes("the cr-ended-secret, with no CR after it"),
        "nothing" to bytes("x-access-token and github.com"),
    )

    /** Each probe a file of its own; what `git grep --no-index -l` lists, by the C locale's rules. */
    private fun foundByGit(probes: Map<String, ByteArray>, pattern: List<String>, input: String? = null): List<String> {
        val dir = Files.createDirectories(base.resolve("probes"))
        probes.forEach { (name, content) -> Files.write(dir.resolve(name), content) }
        val grep = listOf("git", "grep", "--no-index", "-l") + pattern + listOf("--", ".")
        val answer = GitProcess(dir, System.getenv() + ("LC_ALL" to "C")).run(grep, input)
        check(answer.code in 0..1) { "git grep failed with ${answer.code}: ${answer.stderr}" }
        return answer.stdout.lines().filter { it.isNotBlank() }.map { it.removePrefix("./") }.sorted()
    }

    private fun foundHere(probes: Map<String, ByteArray>, patterns: TokenPatterns): List<String> =
        probes.filter { (_, content) -> patterns.foundIn(windowOf(content)) }.keys.sorted()

    private fun patternsOf(line: String): TokenPatterns = TokenPatterns.of(StoredCredential.of("$line\n"))

    private fun classicWith(prefix: Char): String = "gh" + prefix + "_" + "A".repeat(CLASSIC_MINIMUM - 4)

    private fun windowOf(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        /** `ghp_` and 36 characters. */
        const val CLASSIC_MINIMUM = 40

        /** Longer than the longest shortest match, so the stored value is what decides. */
        const val LONGER_THAN_A_SHAPE = 200

        /** A credential line whose secret is not ASCII, so its UTF-8 bytes and its characters differ. */
        const val STORED_WITH_UMLAUTS = "https://someone:pässwörd@example.invalid"

        /** A credential line whose secret decodes to one that ends in a CR, just before its newline on stdin. */
        const val STORED_ENDING_IN_CR = "https://someone:cr-ended-secret%0D@example.invalid"
    }
}
