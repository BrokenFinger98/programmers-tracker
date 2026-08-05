package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layer test for the derived code files (design §5.1). It drives real files under a
 * [TempDir] rather than mocks, because what is being asserted is what ends up on disk:
 * which files exist after a run as opposed to a submit, and the exact text of a diff.
 *
 * Nothing here depends on the clock and nothing sleeps.
 */
class CodeArtifactsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a run refreshes the solution file, which both actions share`() {
        val record = aSubmissionRecord(action = GradingAction.RUN, attempt = 1)

        val file = artifacts().writeLatest(record, CODE_V1)

        file shouldBe problemDirectory().resolve("Solution.java")
        Files.readString(file) shouldBe CODE_V1 + "\n"
    }

    @Test
    fun `a run creates no attempt file — it keeps the previous submit's number`() {
        val record = aSubmissionRecord(action = GradingAction.RUN, attempt = 1)

        artifacts().writeAttempt(record, CODE_V1) shouldBe null

        Files.exists(problemDirectory().resolve("attempts")) shouldBe false
    }

    @Test
    fun `a submit writes its attempt copy under the attempts directory`() {
        val record = aSubmissionRecord(action = GradingAction.SUBMIT, attempt = 2)

        val file = artifacts().writeAttempt(record, CODE_V2)

        file shouldBe problemDirectory().resolve("attempts/002.java")
        Files.readString(file!!) shouldBe CODE_V2 + "\n"
    }

    @Test
    fun `a run before the first submit owns no attempt number and no attempt file`() {
        val record = aSubmissionRecord(action = GradingAction.RUN, attempt = 0)

        artifacts().writeAttempt(record, CODE_V1) shouldBe null
    }

    @Test
    fun `the extension of both files follows the language of the record`() {
        val sql = aSubmissionRecord(language = "mysql", attempt = 1)
        val python = aSubmissionRecord(language = "python3", attempt = 1)

        artifacts().writeLatest(sql, "SELECT 1").fileName.toString() shouldBe "Solution.sql"
        artifacts().writeAttempt(sql, "SELECT 1")!!.fileName.toString() shouldBe "001.sql"
        artifacts().writeLatest(python, "pass").fileName.toString() shouldBe "Solution.py"
    }

    @Test
    fun `writing the same code twice leaves the same bytes and no debris`() {
        val record = aSubmissionRecord(action = GradingAction.RUN, attempt = 1)

        val first = Files.readAllBytes(artifacts().writeLatest(record, CODE_V1)).toList()
        val second = Files.readAllBytes(artifacts().writeLatest(record, CODE_V1)).toList()

        second shouldBe first
        entriesOf(problemDirectory()) shouldBe listOf("Solution.java")
    }

    @Test
    fun `the diff is taken against the previous attempt in the same language`() {
        submitted(attempt = 1, language = "java", code = CODE_V1)
        submitted(attempt = 2, language = "mysql", code = "SELECT 1")
        val third = aSubmissionRecord(attempt = 3, language = "java")

        val diff = artifacts().diffFromPrev(third, CODE_V2)

        diff.shouldNotBeNull()
        diff shouldContain "--- a/attempts/001.java"
        diff shouldContain "+++ b/attempts/003.java"
        diff shouldNotContain "002"
    }

    @Test
    fun `the diff reads as a unified diff`() {
        submitted(attempt = 1, language = "java", code = CODE_V1)
        val second = aSubmissionRecord(attempt = 2, language = "java")

        artifacts().diffFromPrev(second, CODE_V2) shouldBe EXPECTED_DIFF
    }

    @Test
    fun `the first attempt in a language has no diff, even after attempts in another one`() {
        submitted(attempt = 1, language = "mysql", code = "SELECT 1")
        val second = aSubmissionRecord(attempt = 2, language = "java")

        artifacts().diffFromPrev(second, CODE_V1) shouldBe null
    }

    @Test
    fun `a previous attempt whose file is missing yields no diff rather than a fabricated one`() {
        logged(aSubmissionRecord(attempt = 1, language = "java"))
        val second = aSubmissionRecord(attempt = 2, language = "java")

        artifacts().diffFromPrev(second, CODE_V1) shouldBe null
    }

    @Test
    fun `code that did not change yields no diff`() {
        submitted(attempt = 1, language = "java", code = CODE_V1)
        val second = aSubmissionRecord(attempt = 2, language = "java")

        artifacts().diffFromPrev(second, CODE_V1) shouldBe null
    }

    @Test
    fun `a run is never diffed — it keeps the number of an attempt already recorded`() {
        submitted(attempt = 1, language = "java", code = CODE_V1)
        val run = aSubmissionRecord(action = GradingAction.RUN, attempt = 1, language = "java")

        artifacts().diffFromPrev(run, CODE_V2) shouldBe null
    }

    @Test
    fun `a diff longer than the cap is truncated and says so`() {
        submitted(attempt = 1, language = "java", code = numbered("old", 500))
        val second = aSubmissionRecord(attempt = 2, language = "java")

        val diff = artifacts().diffFromPrev(second, numbered("new", 500))!!.lines()

        diff.size shouldBe CodeArtifacts.MAX_DIFF_LINES + 1
        diff.last() shouldContain "truncated"
    }

    @Test
    fun `a file too large to diff cheaply yields no diff at all`() {
        val huge = CodeArtifacts.MAX_DIFF_INPUT_LINES + 1
        submitted(attempt = 1, language = "java", code = numbered("old", huge))
        val second = aSubmissionRecord(attempt = 2, language = "java")

        artifacts().diffFromPrev(second, numbered("new", huge)) shouldBe null
    }

    private fun artifacts() = CodeArtifacts(root, JsonlRecordStore.under(root))

    /** A submit that actually happened — its code on disk and its line in the log. */
    private fun submitted(attempt: Int, language: String, code: String): SubmissionRecord {
        val record = aSubmissionRecord(action = GradingAction.SUBMIT, attempt = attempt, language = language)
        artifacts().writeAttempt(record, code)
        return logged(record)
    }

    private fun logged(record: SubmissionRecord): SubmissionRecord {
        JsonlRecordStore.under(root).append(SubmissionRecordJson.encode(record))
        return record
    }

    private fun problemDirectory(): Path = RecordLayout(root).problemDirectory(aSubmissionRecord().lessonId, TITLE)

    private fun entriesOf(directory: Path): List<String> =
        Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    private companion object {
        val TITLE = aSubmissionRecord().title

        val CODE_V1 = """
            class Solution {
                int solution(int a) {
                    return a;
                }
            }
        """.trimIndent()

        val CODE_V2 = """
            class Solution {
                int solution(int a) {
                    return a * 2;
                }
            }
        """.trimIndent()

        val EXPECTED_DIFF = """
            --- a/attempts/001.java
            +++ b/attempts/002.java
            @@ -1,5 +1,5 @@
             class Solution {
                 int solution(int a) {
            -        return a;
            +        return a * 2;
                 }
             }
        """.trimIndent()

        fun numbered(prefix: String, lines: Int): String = (1..lines).joinToString("\n") { "$prefix $it" }
    }
}
