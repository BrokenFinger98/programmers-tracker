package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.application.RecordedSubmission
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * File-backed [RecordStore] — `log/submissions.jsonl`, append-only.
 *
 * Append-only is what makes the log usable as the attempt authority: a number that was once
 * allocated is never rewritten, so restarting after a crash can only ever under-count, never
 * hand out a number twice.
 *
 * Reads are lenient in the same posture as protocol parsing. A crash mid-append leaves a
 * torn final line, and refusing to read the file because of it would cost every record
 * before it — a grading Programmers has already broadcast can never be fetched again
 * (protocol doc §11).
 */
class JsonlRecordStore(private val file: Path) : RecordStore {
    override fun append(line: String) {
        val record = line.trimEnd('\r', '\n')
        require(record.isNotBlank()) { "a submission record must not be blank" }
        require(!record.contains('\n')) { "a submission record must be one line" }
        Files.createDirectories(file.toAbsolutePath().parent)
        // A torn line has no line break of its own, so healing it here keeps a crash costing
        // one record rather than gluing the next one onto the wreckage and losing both.
        Files.writeString(file, heal() + record + "\n", CHARSET, *APPEND_MODE)
    }

    override fun read(): List<RecordedSubmission> {
        if (!Files.isRegularFile(file)) return emptyList()
        // Decoded with replacement rather than reported: a crash can tear a line in the middle
        // of a multi-byte character, and one bad byte must not cost the whole log.
        val lines = String(Files.readAllBytes(file), CHARSET).lineSequence().filter { it.isNotBlank() }.toList()
        val records = lines.mapNotNull { RecordedSubmission.ofReceived(it) }
        if (records.size < lines.size) {
            logger.warn("Submission log had unreadable lines; kept {} of {}", records.size, lines.size)
        }
        return records
    }

    private fun heal(): String = if (endsMidLine()) "\n" else ""

    private fun endsMidLine(): Boolean {
        val size = runCatching { Files.size(file) }.getOrElse { return false }
        if (size == 0L) return false
        return lastByte(size) != NEWLINE
    }

    private fun lastByte(size: Long): Byte = Files.newByteChannel(file).use { channel ->
        val buffer = ByteBuffer.allocate(1)
        channel.position(size - 1).read(buffer)
        buffer.get(0)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JsonlRecordStore::class.java)
        private val APPEND_MODE = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        private val CHARSET = StandardCharsets.UTF_8
        private const val NEWLINE = '\n'.code.toByte()

        /** The log lives under the record repository, not next to the tool (design §5.1). */
        fun under(recordRoot: Path): JsonlRecordStore = JsonlRecordStore(RecordLayout(recordRoot).submissionLog())
    }
}
