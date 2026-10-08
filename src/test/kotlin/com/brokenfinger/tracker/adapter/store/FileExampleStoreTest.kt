package com.brokenfinger.tracker.adapter.store

import ch.qos.logback.classic.Level
import com.brokenfinger.tracker.domain.ProblemExample
import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.namesIn
import com.brokenfinger.tracker.support.logging.loggedWhile
import com.brokenfinger.tracker.support.logging.warningsWhile
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock

/**
 * `examples.json` — the measured example pairs, beside the problem's `Solution.<ext>`.
 *
 * Server-generated and **replaced whole on every run**, never merged (the README's rule,
 * design §5.5): the judge's current examples are the truth and yesterday's file is not.
 * It is the runner generator's input (#37), so what was measured must survive byte-exactly —
 * including the raw newline a main-style expected output carries (protocol §7.1).
 */
class FileExampleStoreTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `writes the examples beside the problem's solution`() {
        store().replace(120804, "두 수의 곱 구하기", listOf(ProblemExample("6, 7", "42")))

        val file = root.resolve("problems/120804-두-수의-곱-구하기/examples.json")
        Files.readString(file) shouldContain "\"6, 7\""
    }

    @Test
    fun `replaces the whole file on the next run rather than merging`() {
        val store = store()
        store.replace(120804, "두 수의 곱 구하기", listOf(ProblemExample("1, 1", "1")))

        store.replace(120804, "두 수의 곱 구하기", listOf(ProblemExample("6, 7", "42")))

        val text = Files.readString(root.resolve("problems/120804-두-수의-곱-구하기/examples.json"))
        text shouldContain "6, 7"
        text.contains("1, 1").shouldBeFalse()
    }

    /** The §7.1 trap: a raw newline inside a value is data and must survive the round trip. */
    @Test
    fun `a raw newline inside an expected value survives the round trip`() {
        store().replace(181951, "a와 b 출력하기", listOf(ProblemExample("\"4 5\"", "\"a = 4\nb = 5\"")))

        val file = root.resolve("problems/181951-a와-b-출력하기/examples.json")
        val back = Json.decodeFromString<List<ProblemExample>>(Files.readString(file))
        back.single().expected shouldBe "\"a = 4\nb = 5\""
    }

    /**
     * A grading with no examples writes nothing and deletes nothing: submits announce no
     * examples, and a submit right after a run must not blank the file the run just wrote.
     */
    @Test
    fun `no examples means the existing file is left alone`() {
        val store = store()
        store.replace(120804, "두 수의 곱 구하기", listOf(ProblemExample("6, 7", "42")))

        store.replace(120804, "두 수의 곱 구하기", emptyList())

        Files.readString(root.resolve("problems/120804-두-수의-곱-구하기/examples.json")) shouldContain "6, 7"
    }

    /** Best-effort like the raw move: losing this file must never cost the record. */
    @Test
    fun `an unwritable directory does not throw`() {
        val blocked = root.resolve("problems")
        Files.createFile(blocked) // a FILE where the directory should be — every mkdir now fails

        store().replace(120804, "두 수의 곱 구하기", listOf(ProblemExample("6, 7", "42")))
    }

    // Written over a link, never through one (#361) ---------------------------------------------------

    @Test
    fun `examples that are a link are replaced, and the file they led to keeps its bytes`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val examples = aLink(root.resolve("problems/$DIRECTORY/examples.json"), token)

        val heard = warningsWhile(RecordWrites::class) { store().replace(120804, TITLE, SIX_TIMES_SEVEN) }

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.isSymbolicLink(examples) shouldBe false
        Files.readString(examples) shouldContain "\"6, 7\""
        heard.single() shouldContain examples.toString()
    }

    /** Best effort as before: a refused file is said, and the record it rode in with is not the store's to fail. */
    @Test
    fun `a problem directory that is a link gets no examples, and nothing is written where it leads`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        aLink(root.resolve("problems/$DIRECTORY"), outside)

        val heard = warningsWhile(RecordWrites::class) { store().replace(120804, TITLE, SIX_TIMES_SEVEN) }

        namesIn(outside).shouldBeEmpty()
        heard.single() shouldContain "problems/$DIRECTORY is a symbolic link"
    }

    @Test
    fun `examples that are a dangling link are replaced, and nothing is created where they pointed`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val nowhere = outside.resolve("made-by-a-run.json")
        aLink(root.resolve("problems/$DIRECTORY/examples.json"), nowhere)

        store().replace(120804, TITLE, SIX_TIMES_SEVEN)

        Files.exists(nowhere, NOFOLLOW_LINKS) shouldBe false
    }

    /**
     * The route #361's comment found. The read bound (#354) refuses a linked `examples.json`, so the runner says to
     * press Run Code — and pressing it is this write, which went through the same link and overwrote what it led to.
     * Now the link is replaced, and the next attachment generates the runner from the examples just captured.
     */
    @Test
    fun `pressing Run Code after linked examples were refused replaces the link rather than writing through it`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val token = aPushTokenIn(root)
        val examples = aLink(root.resolve("problems/$DIRECTORY/examples.json"), token)
        val artifacts = FileDerivedArtifacts(root, JsonlRecordStore.under(root), Clock.systemDefaultZone())
        val said = loggedWhile(FileDerivedArtifacts::class, Level.INFO) { artifacts.writeRunner(aCppRecord(), CPP) }
        said.single() shouldContain "press Run Code"

        store().replace(120804, TITLE, SIX_TIMES_SEVEN)
        artifacts.writeRunner(aCppRecord(), CPP)

        Files.readString(token) shouldBe "$A_PUSH_TOKEN_LINE\n"
        Files.isSymbolicLink(examples) shouldBe false
        Files.readString(examples.resolveSibling("runner_test.cpp")) shouldContain "solution(arg1, arg2)"
    }

    private fun aCppRecord() = aSubmissionRecord(language = "cpp")

    private fun store() = FileExampleStore(RecordLayout(root))

    private companion object {
        const val TITLE = "두 수의 곱 구하기"
        const val DIRECTORY = "120804-두-수의-곱-구하기"
        const val CPP = "int solution(int num1, int num2) { return num1 * num2; }"
        val SIX_TIMES_SEVEN = listOf(ProblemExample("6, 7", "42"))
    }
}
