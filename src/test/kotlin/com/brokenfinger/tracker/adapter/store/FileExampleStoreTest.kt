package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.ProblemExample
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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

    private fun store() = FileExampleStore(RecordLayout(root))
}
