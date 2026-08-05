package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.ProblemTimer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * File-backed [ProblemTimer] — `.ps/timers.json`, a lesson id to first-seen epoch second map.
 *
 * The document is rewritten whole on every new problem, so it goes through [AtomicStateFile]:
 * a plain write leaves a window in which a reader sees a truncated document, which looks like
 * data rather than like a failure ([[decisions/2026-08-05-write-serialization.md]] decision 3).
 *
 * Reads are lenient in the same posture as protocol parsing. An entry that cannot be read is
 * dropped with a warning instead of failing the call: the cost of a lost start time is one
 * wrong `elapsedSec`, while the cost of throwing here is a capture that never happens — and a
 * grading Programmers has already broadcast is gone for good (protocol §11).
 */
class FileProblemTimer(private val file: AtomicStateFile, private val clock: Clock) : ProblemTimer {
    override fun elapsedSecOf(lessonId: Long): Long {
        val startedAt = startTimes()[key(lessonId)] ?: return 0
        val elapsed = Duration.between(Instant.ofEpochSecond(startedAt), clock.instant())
        // A clock that moved backwards must not produce a negative duration in a record.
        return elapsed.seconds.coerceAtLeast(0)
    }

    /** Synchronized because this is a read-modify-write; the file guards readers, not writers. */
    @Synchronized
    override fun startIfAbsent(lessonId: Long) {
        val current = startTimes()
        if (current.containsKey(key(lessonId))) return
        file.write(encode(current + (key(lessonId) to clock.instant().epochSecond)))
    }

    private fun startTimes(): Map<String, Long> {
        val text = file.read() ?: return emptyMap()
        val document = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { return unreadable() }
        val entries = document.entries.mapNotNull { (lesson, value) -> startTime(value)?.let { lesson to it } }
        warnDropped(document.size - entries.size)
        return entries.toMap()
    }

    private fun startTime(value: JsonElement): Long? = (value as? JsonPrimitive)?.longOrNull

    private fun warnDropped(dropped: Int) {
        if (dropped <= 0) return
        logger.warn("Dropped {} unreadable entries from the timer document", dropped)
    }

    private fun unreadable(): Map<String, Long> {
        logger.warn("Timer document at {} is unreadable; treating it as empty", file)
        return emptyMap()
    }

    // Sorted so the document has one canonical form and a diff shows only what actually changed.
    private fun encode(startTimes: Map<String, Long>): String =
        Json.encodeToString(startTimes.toList().sortedBy { (lesson, _) -> lesson }.toMap())

    private fun key(lessonId: Long): String = lessonId.toString()

    companion object {
        private const val TIMERS = "timers.json"

        private val logger = LoggerFactory.getLogger(FileProblemTimer::class.java)

        /** Timers live under the record repository, next to the records they describe (design §5.1). */
        fun under(recordRoot: Path, clock: Clock): FileProblemTimer =
            FileProblemTimer(AtomicStateFile.under(recordRoot, TIMERS), clock)
    }
}
