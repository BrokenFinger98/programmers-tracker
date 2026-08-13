package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.AttachedCode
import com.brokenfinger.tracker.application.DerivedArtifacts
import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.domain.ProblemExample
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.calc.TagCount
import com.brokenfinger.tracker.domain.calc.runner.CRunner
import com.brokenfinger.tracker.domain.calc.runner.CppRunner
import com.brokenfinger.tracker.domain.calc.runner.CsharpRunner
import com.brokenfinger.tracker.domain.calc.runner.JavaRunner
import com.brokenfinger.tracker.domain.calc.runner.JavascriptRunner
import com.brokenfinger.tracker.domain.calc.runner.KotlinRunner
import com.brokenfinger.tracker.domain.calc.runner.PythonRunner
import com.brokenfinger.tracker.domain.calc.runner.Runner
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The store's [DerivedArtifacts] — [CodeArtifacts] for the code files and [ProblemReadme] for
 * the page, joined so `application` sees one port instead of the layout's internals.
 *
 * It adds no rules of its own. Which files a run and a submit each own is [CodeArtifacts]'
 * decision (design §5.1), and what the page says is [ProblemReadme]'s; all this class settles
 * is **which file the record points at**, and that it points at it relatively.
 *
 * Thread confinement is the caller's: every method here is called from inside stage 3's
 * confined section ([[decisions/2026-08-05-write-serialization]] decision 1).
 */
class FileDerivedArtifacts(private val recordRoot: Path, records: RecordStore) : DerivedArtifacts {
    private val artifacts = CodeArtifacts(recordRoot, records)
    private val layout = RecordLayout(recordRoot)
    private val readme = ProblemReadme(layout)
    private val tagNotes = TagNotes(layout)

    /**
     * The attempt copy is what a submit points at: it is the code of *that* grading and never
     * changes again, while `Solution.<ext>` is overwritten by the next run. A run owns no
     * attempt file, so it points at the solution file it just refreshed.
     *
     * The diff is taken before the attempt copy is written for readability only — it compares
     * against the *previous* attempt, so the order does not matter to the result.
     */
    override fun writeCode(record: SubmissionRecord, code: String): AttachedCode {
        val diff = artifacts.diffFromPrev(record, code)
        val latest = artifacts.writeLatest(record, code)
        val attempt = artifacts.writeAttempt(record, code)
        return AttachedCode(relativeOf(attempt ?: latest), diff)
    }

    /**
     * The runner rides the same trigger as the code it tests: regenerated on every
     * attachment, from the *current* code and the *captured* examples, and a refusal removes
     * the stale file rather than leaving a runner that tests yesterday's solution. Reasons go
     * to the log — a best-effort runner that compiles and tests the wrong thing is the one
     * outcome #37 forbids.
     *
     * Seven languages today, in the owner's measured support order — java, python3, cpp,
     * javascript, kotlin, c, csharp — each earned by an execution suite against its real
     * toolchain. Anything else logs once per attachment and writes nothing.
     */
    override fun writeRunner(record: SubmissionRecord, code: String) {
        val directory = layout.problemDirectory(record.lessonId, record.title)
        val generate = GENERATORS[record.language]
        if (generate == null) {
            logger.info("Lesson {}: no runner — {} is not yet supported (#37)", record.lessonId, record.language)
            return
        }
        when (val runner = generate(code, examplesOf(directory))) {
            is Runner.Generated -> runCatching {
                Files.createDirectories(directory)
                Files.writeString(directory.resolve(runner.fileName), runner.source)
                runner.extras.forEach { extra -> Files.writeString(directory.resolve(extra.fileName), extra.source) }
            }.onFailure { logger.warn("Lesson {}: the runner could not be written", record.lessonId, it) }
            is Runner.Refused -> {
                RUNNER_FILES.forEach { stale -> runCatching { Files.deleteIfExists(directory.resolve(stale)) } }
                logger.info("Lesson {}: no runner — {}", record.lessonId, runner.reason)
            }
        }
    }

    /** The pairs stage 2 stored; an unreadable file means no examples, which refuses cleanly. */
    private fun examplesOf(directory: Path): List<ProblemExample> = runCatching {
        json.decodeFromString<List<ProblemExample>>(Files.readString(directory.resolve("examples.json")))
    }.getOrDefault(emptyList())

    /**
     * Written once and never again. A second grading of the same problem finds the file there
     * and leaves it — so a statement the reader annotated, or one Programmers has since
     * reworded, is not overwritten by whichever page a later fetch happened to see.
     */
    override fun writeStatement(record: SubmissionRecord, markdown: String) {
        val file = layout.statementFile(record.lessonId, record.title)
        if (Files.exists(file)) return
        Files.createDirectories(file.parent)
        Files.writeString(file, markdown.trimEnd() + "\n")
    }

    override fun writeReadme(records: List<SubmissionRecord>) {
        readme.write(records)
    }

    override fun writeTagNotes(counts: List<TagCount>) {
        tagNotes.write(counts)
    }

    // Forward slashes whatever the host uses — the same shape `rawPath` already has, so a
    // record repository cloned onto another machine still resolves every path it carries.
    private fun relativeOf(path: Path): String =
        recordRoot.toAbsolutePath().relativize(path.toAbsolutePath()).joinToString("/")

    private companion object {
        /** The supported generators, in the measured order (#37) — a language lands here only
         *  after its execution suite has actually run its output. */
        val GENERATORS = mapOf<String, (String, List<ProblemExample>) -> Runner>(
            "java" to JavaRunner::generate,
            "python3" to PythonRunner::generate,
            "cpp" to CppRunner::generate,
            "javascript" to JavascriptRunner::generate,
            "kotlin" to KotlinRunner::generate,
            "c" to CRunner::generate,
            "csharp" to CsharpRunner::generate,
        )

        /** Every name a stale runner can have; a refusal clears them all. */
        val RUNNER_FILES = listOf(
            "RunnerTest.java",
            "runner_test.py",
            "runner_test.cpp",
            "runner_test.js",
            "runner_test.kt",
            "runner_test.c",
            "runner_test.cs",
            "runner_test.csproj",
        )

        val json = Json { ignoreUnknownKeys = true }

        val logger = LoggerFactory.getLogger(FileDerivedArtifacts::class.java)!!
    }
}
