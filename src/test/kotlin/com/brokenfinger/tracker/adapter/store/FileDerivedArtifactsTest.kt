package com.brokenfinger.tracker.adapter.store

import ch.qos.logback.classic.Level
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.NOT_OURS
import com.brokenfinger.tracker.support.fixtures.aFileNotOurs
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.logging.loggedWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock

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

    @TempDir
    lateinit var outside: Path

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
    fun `a run also leaves its code in the run log`() {
        val record = aSubmissionRecord(action = GradingAction.RUN, attempt = 0)

        artifacts().writeCode(record, CODE_V1)

        val log = RecordLayout(root).runLog(120804, "두 수의 곱 구하기")
        Files.readAllLines(log).single() shouldContain record.recordId()
    }

    @Test
    fun `a submit leaves no run log`() {
        artifacts().writeCode(aSubmissionRecord(action = GradingAction.SUBMIT, attempt = 2), CODE_V1)

        Files.exists(RecordLayout(root).runLog(120804, "두 수의 곱 구하기")) shouldBe false
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

        // No examples.json stored → no examples could be read, so no runner.
        artifacts().writeRunner(aSubmissionRecord(language = "cpp"), "int solution(int a) { return a; }")

        staleRunners.forEach { Files.exists(directory.resolve(it)) shouldBe false }
    }

    /**
     * Example values are written into a runner, which is committed and pushed, so `examples.json` is held
     * to the bound every reader under `problems/` is (#354). A target that does not decode as examples
     * leaks nothing anyway; this one does, so only the bound refuses it.
     */
    @Test
    fun `examples that are a link out of the problems directory generate no runner`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val directory = root.resolve("problems/120804-두-수의-곱-구하기")
        val elsewhere = Files.createDirectories(root.resolve(".ps")).resolve("examples.json")
        Files.writeString(elsewhere, """[{"input": "6, 7", "expected": "42"}]""")
        aLink(directory.resolve("examples.json"), elsewhere)

        Files.readString(directory.resolve("examples.json")) shouldContain "6, 7"
        artifacts().writeRunner(
            aSubmissionRecord(language = "cpp"),
            "int solution(int num1, int num2) { return num1 * num2; }",
        )

        Files.exists(directory.resolve("runner_test.cpp")) shouldBe false
    }

    /**
     * Every generator calls an empty list "never captured", which only this side can judge: an
     * `examples.json` that was refused (#354) was captured. So the reason logged claims only that none
     * could be read, and the refusal's own warning says why.
     */
    @Test
    fun `examples that cannot be read are reported as unread, not as never captured`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/120804-두-수의-곱-구하기/examples.json"), aPushTokenIn(root))

        val said = loggedWhile(FileDerivedArtifacts::class, Level.INFO) {
            artifacts().writeRunner(aSubmissionRecord(language = "cpp"), "int solution(int a) { return a; }")
        }

        said.single() shouldContain "no examples could be read"
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

    // Written over a link, never through one (#361) ---------------------------------------------------

    /**
     * A linked statement reads as absent (#354), so it was fetched again at every boot, while this writer saw a
     * file there through the link, wrote nothing, and was counted as having filled it. A link is not a statement
     * anyone wrote, so the write-once rule does not keep it: it is replaced, once.
     */
    @Test
    fun `a statement that is a link is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val statement = aLink(statementFile(), token)

        val heard = warningsWhile(RecordWrites::class) { artifacts().writeStatement(aSubmissionRecord(), PROBLEM) }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.isSymbolicLink(statement) shouldBe false
        Files.readString(statement) shouldBe "the problem\n"
        heard.single() shouldContain statement.toString()
    }

    /** Measured in #354's review: a dangling `statement.md` made this writer create a file outside `problems/`. */
    @Test
    fun `a statement that is a dangling link is replaced, and nothing is created where it pointed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-statement.md")
        val statement = aLink(statementFile(), nowhere)

        val heard = warningsWhile(RecordWrites::class) { artifacts().writeStatement(aSubmissionRecord(), PROBLEM) }

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.readString(statement) shouldBe "the problem\n"
        heard.single() shouldContain statement.toString()
    }

    /** Thrown, so the statement backfill counts the problem as not filled, which it was not. */
    @Test
    fun `a problem directory that is a link gets no statement, and nothing is written where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/$DIRECTORY"), outside)

        val heard = warningsWhile(RecordWrites::class) {
            shouldThrow<RefusedWriteException> { artifacts().writeStatement(aSubmissionRecord(), PROBLEM) }
        }

        namesIn(outside).shouldBeEmpty()
        heard.single() shouldContain "problems/$DIRECTORY is a symbolic link"
    }

    /** The runner is committed and pushed, and was written through a link standing where it should be. */
    @Test
    fun `a runner that is a link is replaced, and the file it led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val runner = aLink(problemWithExamples().resolve("runner_test.cpp"), token)

        val heard = warningsWhile(RecordWrites::class) { artifacts().writeRunner(aCppRecord(), CPP) }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.readString(runner) shouldContain "solution(arg1, arg2)"
        heard.single() shouldContain runner.toString()
    }

    @Test
    fun `a runner that is a dangling link is replaced, and nothing is created where it pointed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-runner.cpp")
        val runner = aLink(problemWithExamples().resolve("runner_test.cpp"), nowhere)

        artifacts().writeRunner(aCppRecord(), CPP)

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
        Files.readString(runner) shouldContain "solution(arg1, arg2)"
    }

    /**
     * A read may follow a link that stays inside `problems/` (#354), so the examples are found through one. The
     * runner beside them is written through no link at all: it is skipped, and said.
     */
    @Test
    fun `no runner is written through a problem directory linked to another problem's`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val other = Files.createDirectories(root.resolve("problems/1-other"))
        Files.writeString(other.resolve("examples.json"), EXAMPLES)
        aLink(root.resolve("problems/$DIRECTORY"), other)

        val heard = warningsWhile(RecordWrites::class) { artifacts().writeRunner(aCppRecord(), CPP) }

        namesIn(other) shouldBe listOf("examples.json")
        heard.single() shouldContain "problems/$DIRECTORY is a symbolic link"
    }

    /** The stale-runner sweep deleted files of a runner's names wherever a linked problem directory led. */
    @Test
    fun `no stale runner is swept through a problem directory that is a link`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val elsewhere = aFileNotOurs(outside, "RunnerTest.java")
        aLink(root.resolve("problems/$DIRECTORY"), outside)

        val heard = warningsWhile(RecordWrites::class) { artifacts().writeRunner(aCppRecord(), CPP) }

        Files.readString(elsewhere) shouldBe NOT_OURS
        heard.single() shouldContain "problems/$DIRECTORY is a symbolic link"
    }

    private fun problemWithExamples(): Path {
        val directory = Files.createDirectories(root.resolve("problems/$DIRECTORY"))
        Files.writeString(directory.resolve("examples.json"), EXAMPLES)
        return directory
    }

    private fun aCppRecord() = aSubmissionRecord(language = "cpp")

    private fun statementFile(): Path =
        RecordLayout(root).statementFile(aSubmissionRecord().lessonId, aSubmissionRecord().title)

    private fun artifacts() = FileDerivedArtifacts(root, JsonlRecordStore.under(root), Clock.systemDefaultZone())

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

        const val DIRECTORY = "120804-두-수의-곱-구하기"
        const val CPP = "int solution(int num1, int num2) { return num1 * num2; }"
        const val EXAMPLES = """[{"input": "6, 7", "expected": "42"}]"""
        const val PROBLEM = "the problem"
    }
}
