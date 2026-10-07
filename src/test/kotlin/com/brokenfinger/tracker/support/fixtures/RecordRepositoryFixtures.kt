package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.store.FileGradingCodes
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.adapter.store.RunLog
import com.brokenfinger.tracker.application.ProblemCatalog
import com.brokenfinger.tracker.application.RawSessionLog
import com.brokenfinger.tracker.application.RecordQuery
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Clock
import java.time.OffsetDateTime

/**
 * A real record repository on disk (dev rules §6.4). Records go in through the real store,
 * so a test reads back exactly what the capture path would have written — the shapes here
 * are the ones a user actually has, starting with the empty repository of day one.
 */
class RecordRepositoryFixture(val root: Path) {
    fun containing(vararg records: SubmissionRecord): RecordRepositoryFixture = apply {
        records.forEach { store().append(SubmissionRecordJson.encode(it)) }
    }

    /**
     * Appends a line the way a crash mid-append leaves one — cut off mid-value, with **no
     * closing line break**. Written past the store on purpose: `append` heals a torn tail
     * before writing, so going through it would produce a whole line and prove nothing.
     */
    fun tornBy(fragment: String): RecordRepositoryFixture = apply {
        Files.createDirectories(logFile().parent)
        Files.writeString(logFile(), fragment, UTF_8, CREATE, APPEND)
    }

    fun store(): JsonlRecordStore = JsonlRecordStore(logFile())

    /** Catalogue-free by default: most read tests are about records, not about browsing. */
    fun query(
        catalog: ProblemCatalog = anEmptyCatalog(),
        clock: Clock = Clock.systemUTC(),
        raw: RawSessionLog = FileRawSessionLog.under(root),
    ): RecordQuery = RecordQuery(store(), catalog, clock, raw, codes = FileGradingCodes(RecordLayout(root)))

    /** A run's code, kept the way the capture path keeps it — through the real [RunLog]. */
    fun withRunCode(
        record: SubmissionRecord,
        code: String,
        attachedAt: OffsetDateTime = record.ts.plusNanos(MEASURED_FETCH_NANOS),
    ): RecordRepositoryFixture = apply {
        RunLog(RecordLayout(root), Clock.fixed(attachedAt.toInstant(), attachedAt.offset)).append(record, code)
    }

    /** A submit's attempt file, at the path its record carries. */
    fun withSubmitCode(record: SubmissionRecord, code: String): RecordRepositoryFixture = apply {
        val file = root.resolve(requireNotNull(record.codePath) { "a submit with code carries its path" })
        Files.createDirectories(file.parent)
        Files.writeString(file, code)
    }

    fun logFile(): Path = RecordLayout(root).submissionLog()

    private companion object {
        // Measured on lessons 59035 (0.24-0.53 s) and 59036 (0.27-0.38 s): the code lands this long
        // after the record, well before any human could press Run again.
        const val MEASURED_FETCH_NANOS = 300_000_000L
    }
}

fun aRecordRepository(root: Path): RecordRepositoryFixture = RecordRepositoryFixture(root)

/**
 * The tail of a record whose write was cut short. It is not valid JSON, which is what a
 * half-written line actually looks like.
 */
fun aTornRecordLine(): String = """{"ts":"2026-08-06T01:12:44+09:00","lessonId":120804,"title":"two nu"""

/**
 * A whole line that the log reader accepts but the full-record reader cannot use — it
 * carries an identity and nothing else. The other way a read can come up short.
 */
fun aPartialRecordLine(lessonId: Long = 120804): String =
    """{"lessonId":$lessonId,"action":"submit","attempt":1,"language":"java"}"""
