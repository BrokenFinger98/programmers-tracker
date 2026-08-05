package com.brokenfinger.tracker.adapter.store

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RecordLayoutTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a problem directory is the lesson id plus a slug of the title`() {
        directoryName(120804, "두 수의 곱 구하기") shouldBe "120804-두-수의-곱-구하기"
    }

    @Test
    fun `the slug lowercases and hyphenates`() {
        directoryName(120804, "Two Sum II") shouldBe "120804-two-sum-ii"
    }

    @Test
    fun `punctuation collapses into single hyphens with no leading or trailing one`() {
        directoryName(1, "[Lv0]  A + B?? ") shouldBe "1-lv0-a-b"
    }

    @Test
    fun `characters Windows reserves never reach the name`() {
        val name = directoryName(1, """a<b>c:d"e/f\g|h?i*j""")

        name shouldBe "1-a-b-c-d-e-f-g-h-i-j"
    }

    @Test
    fun `a name can never end in a dot or a space, which Windows silently strips`() {
        directoryName(1, "trailing dot.") shouldBe "1-trailing-dot"
    }

    @Test
    fun `a very long title is capped so the path stays within Windows limits`() {
        val name = directoryName(120804, "word ".repeat(60))

        name.length shouldBeLessThanOrEqualTo "120804-".length + RecordLayout.MAX_SLUG
        name.length shouldBeGreaterThan "120804-".length
        name shouldMatch WINDOWS_SAFE
    }

    @Test
    fun `a title with nothing sluggable leaves the lesson id standing alone`() {
        directoryName(120804, "???") shouldBe "120804"
        directoryName(120804, null) shouldBe "120804"
    }

    @Test
    fun `a renamed problem keeps its directory, so its history never splits`() {
        val original = layout().problemDirectory(120804, "두 수의 곱 구하기")
        Files.createDirectories(original)

        layout().problemDirectory(120804, "Product of Two Numbers") shouldBe original
    }

    @Test
    fun `a different problem whose id shares a prefix is not mistaken for it`() {
        Files.createDirectories(layout().problemDirectory(1208, "old"))

        directoryName(120804, "new") shouldBe "120804-new"
    }

    @Test
    fun `an attempt file is numbered to three digits under the attempts directory`() {
        val file = layout().attemptFile(120804, "곱", 2, "java")

        file shouldBe layout().problemDirectory(120804, "곱").resolve("attempts/002.java")
    }

    @Test
    fun `the extension follows the language of the record`() {
        extensionOf("java") shouldBe "java"
        extensionOf("python3") shouldBe "py"
        extensionOf("mysql") shouldBe "sql"
        extensionOf("oracle") shouldBe "sql"
        extensionOf("javascript") shouldBe "js"
        extensionOf("kotlin") shouldBe "kt"
        extensionOf("csharp") shouldBe "cs"
        extensionOf("ruby") shouldBe "rb"
        extensionOf("cpp") shouldBe "cpp"
    }

    @Test
    fun `a language we have never seen keeps its own name rather than being dropped`() {
        extensionOf("rust") shouldBe "rust"
    }

    @Test
    fun `an unusable language falls back instead of producing an illegal name`() {
        extensionOf(null) shouldBe "txt"
        extensionOf("  ") shouldBe "txt"
        extensionOf("../etc") shouldBe "etc"
    }

    @Test
    fun `numbering past three digits widens rather than truncates`() {
        attemptName(1000, "java") shouldBe "1000.java"
    }

    @Test
    fun `an attempt number below one is rejected — a run belongs to no attempt file`() {
        shouldThrow<IllegalArgumentException> { layout().attemptFile(120804, "x", 0, "java") }
    }

    @Test
    fun `the raw frames of an attempt sit beside its code`() {
        attemptRawName(2) shouldBe "002.raw.jsonl"
    }

    @Test
    fun `every generated name is legal on Windows`() {
        directoryName(120804, """두 수의 곱: a/b\c*d?e""") shouldMatch WINDOWS_SAFE
        attemptName(3, "mysql") shouldMatch WINDOWS_SAFE
    }

    @Test
    fun `the submission log lives under the record repository`() {
        layout().submissionLog() shouldBe root.resolve("log/submissions.jsonl")
    }

    private fun layout() = RecordLayout(root)

    private fun directoryName(lessonId: Long, title: String?): String =
        layout().problemDirectory(lessonId, title).fileName.toString()

    private fun attemptName(attempt: Int, language: String?): String =
        layout().attemptFile(120804, "x", attempt, language).fileName.toString()

    private fun attemptRawName(attempt: Int): String = layout().rawAttemptFile(120804, "x", attempt).fileName.toString()

    private fun extensionOf(language: String?) = RecordLayout.extensionOf(language)

    private companion object {
        // No reserved character, no control character, no trailing dot or space.
        val WINDOWS_SAFE = Regex("""[^<>:"/\\|?*\x00-\x1F]*[^<>:"/\\|?*\x00-\x1F. ]""")
    }
}
