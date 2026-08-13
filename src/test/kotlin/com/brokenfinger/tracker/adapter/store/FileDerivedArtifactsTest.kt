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
 * Layer test for the store's side of stage 3 (dev rules §6.1) — real files under a [TempDir],
 * because what is asserted is what a record ends up pointing at.
 *
 * The path shape is the point of this class: a record repository is meant to be cloned onto
 * another machine, so a `codePath` leaves here relative and forward-slashed, exactly as
 * `rawPath` already does.
 */
class FileDerivedArtifactsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a submit points at its attempt copy, which is the code of that grading and never changes`() {
        val record = aSubmissionRecord(action = GradingAction.SUBMIT, attempt = 2)

        val written = artifacts().writeCode(record, CODE_V1)

        written.codePath shouldBe "problems/120804-두-수의-곱-구하기/attempts/002.java"
        Files.readString(root.resolve(written.codePath)) shouldBe CODE_V1 + "\n"
    }

    @Test
    fun `a run points at the solution file, the only one it owns`() {
        val record = aSubmissionRecord(action = GradingAction.RUN, attempt = 0)

        val written = artifacts().writeCode(record, CODE_V1)

        written.codePath shouldBe "problems/120804-두-수의-곱-구하기/Solution.java"
        written.diffFromPrev shouldBe null
    }

    @Test
    fun `a path never leaves here absolute, whatever the host separator is`() {
        val written = artifacts().writeCode(aSubmissionRecord(attempt = 1), CODE_V1)

        written.codePath shouldNotContain "\\"
        Path.of(written.codePath).isAbsolute shouldBe false
    }

    @Test
    fun `the diff against the previous attempt in the same language is reported`() {
        stored(aSubmissionRecord(attempt = 1))
        artifacts().writeCode(aSubmissionRecord(attempt = 1), CODE_V1)
        stored(aSubmissionRecord(attempt = 2))

        val written = artifacts().writeCode(aSubmissionRecord(attempt = 2), CODE_V2)

        written.diffFromPrev.shouldNotBeNull() shouldContain "+        return (long) num1 * num2;"
    }

    @Test
    fun `a cpp record gets its runner generated from the stored examples`() {
        val directory = root.resolve("problems/120804-두-수의-곱-구하기")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("examples.json"), """[{"input": "6, 7", "expected": "42"}]""")

        artifacts().writeRunner(
            aSubmissionRecord(language = "cpp"),
            "int solution(int num1, int num2) { return num1 * num2; }",
        )

        Files.readString(directory.resolve("runner_test.cpp")) shouldContain "solution(arg1, arg2)"
    }

    /** C# is the two-artifact runner — the project file must land beside the harness. */
    @Test
    fun `a csharp record gets its runner and project file`() {
        val directory = root.resolve("problems/120804-두-수의-곱-구하기")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("examples.json"), """[{"input": "6, 7", "expected": "42"}]""")

        artifacts().writeRunner(
            aSubmissionRecord(language = "csharp"),
            "public class Solution {\n    public int solution(int num1, int num2) { return num1 * num2; }\n}",
        )

        Files.readString(directory.resolve("runner_test.cs")) shouldContain "new Solution().solution"
        val project = Files.readString(directory.resolve("runner_test.csproj"))
        project shouldContain "<StartupObject>RunnerTest</StartupObject>"
    }

    /**
     * The sweep must clear every runner name, not just the current language's: a problem
     * re-solved in another language leaves the previous language's runner behind, and a
     * stale runner that still passes is worse than none. The names are spelled out here on
     * purpose — a list shared with production would sweep whatever production says,
     * tautologically.
     */
    @Test
    fun `a refusal sweeps stale runners of every language`() {
        val staleRunners = listOf(
            "RunnerTest.java",
            "runner_test.py",
            "runner_test.cpp",
            "runner_test.js",
            "runner_test.kt",
            "runner_test.c",
            "runner_test.cs",
            "runner_test.csproj",
        )
        val directory = root.resolve("problems/120804-두-수의-곱-구하기")
        Files.createDirectories(directory)
        staleRunners.forEach { Files.writeString(directory.resolve(it), "stale") }

        // No examples.json stored → the generator refuses (no examples captured).
        artifacts().writeRunner(aSubmissionRecord(language = "cpp"), "int solution(int a) { return a; }")

        staleRunners.forEach { Files.exists(directory.resolve(it)) shouldBe false }
    }

    @Test
    fun `the README of a problem is written from the records it is given`() {
        artifacts().writeReadme(listOf(aSubmissionRecord(attempt = 2)))

        val readme = root.resolve("problems/120804-두-수의-곱-구하기/README.md")
        Files.readString(readme) shouldContain "lessonId: 120804"
    }

    private fun stored(record: SubmissionRecord) {
        JsonlRecordStore.under(root).append(SubmissionRecordJson.encode(record))
    }

    /**
     * The statement is written **once** (#275). Every other artifact here is regenerated from
     * the newest grading; this one is not, because the README that links it is — and a statement
     * living in a regenerated file would have to be re-fetched on every submit to survive.
     */
    @Test
    fun `the statement is written once and left alone afterwards`() {
        val record = aSubmissionRecord()

        artifacts().writeStatement(record, "the problem, as Programmers worded it")
        artifacts().writeStatement(record, "a later fetch, of a page since reworded")

        Files.readString(statementFile()).trim() shouldBe "the problem, as Programmers worded it"
    }

    @Test
    fun `a statement lands beside the attempts it describes`() {
        val record = aSubmissionRecord()

        artifacts().writeStatement(record, "body")

        statementFile() shouldBe RecordLayout(root).problemDirectory(record.lessonId, record.title)
            .resolve("statement.md")
    }

    /** One trailing newline, so appending to it by hand does not need a leading blank line. */
    @Test
    fun `it ends with exactly one newline`() {
        artifacts().writeStatement(aSubmissionRecord(), "body\n\n\n")

        Files.readString(statementFile()) shouldBe "body\n"
    }

    private fun statementFile(): Path =
        RecordLayout(root).statementFile(aSubmissionRecord().lessonId, aSubmissionRecord().title)

    private fun artifacts() = FileDerivedArtifacts(root, JsonlRecordStore.under(root))

    private companion object {
        val CODE_V1 =
            """
            class Solution {
                public long solution(int num1, int num2) {
                    return num1 * num2;
                }
            }
            """.trimIndent()

        val CODE_V2 = CODE_V1.replace("return num1 * num2;", "return (long) num1 * num2;")
    }
}
