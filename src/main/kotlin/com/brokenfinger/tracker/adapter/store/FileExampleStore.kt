package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.ExampleStore
import com.brokenfinger.tracker.domain.ProblemExample
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * `examples.json` beside the problem's `Solution.<ext>` — replaced whole on every run,
 * never merged, like every other server-generated artifact (design §5.5).
 *
 * Values are stored exactly as measured. They are JSON-*like*, not JSON (protocol §7.1 —
 * a main-style expected output carries a raw newline inside its quotes), which is precisely
 * why they are stored as opaque strings inside real JSON here: kotlinx escapes the newline
 * on the way out and restores it on the way in, and the measurement survives byte-exact.
 *
 * Best-effort like the raw-file move: this file regenerates on the next run, so losing a
 * write must never cost the record it rode in with.
 */
class FileExampleStore(private val layout: RecordLayout) : ExampleStore {
    override fun replace(lessonId: Long, title: String?, examples: List<ProblemExample>) {
        if (examples.isEmpty()) return
        runCatching {
            val file = examplesFileOf(lessonId, title)
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(examples))
        }.onFailure { logger.warn("Lesson {} was recorded but its examples were not written", lessonId, it) }
    }

    private fun examplesFileOf(lessonId: Long, title: String?): Path =
        layout.problemDirectory(lessonId, title).resolve(FILE)

    private companion object {
        const val FILE = "examples.json"

        // Pretty on purpose: the file sits in a git repository a human diffs, and a run that
        // changed one example should read as one changed line.
        val json = Json { prettyPrint = true }

        val logger = LoggerFactory.getLogger(FileExampleStore::class.java)!!
    }
}
