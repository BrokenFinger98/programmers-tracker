package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.ProblemTimer
import com.brokenfinger.tracker.domain.SensorObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One problem's browser-side state: when it was first seen, and what the sensor last said. */
private data class ProblemState(val startedAt: Long, val observation: SensorObservation? = null)

/**
 * File-backed [ProblemTimer] — `.ps/timers.json`, a lesson id to per-problem state map.
 *
 * The document is rewritten whole on every change, so it goes through [AtomicStateFile]: a
 * plain write leaves a window in which a reader sees a truncated document, which looks like
 * data rather than like a failure ([[decisions/2026-08-05-write-serialization.md]] decision 3).
 *
 * **Two entry shapes are read, one is written.** The document used to map a lesson straight
 * to its epoch second, and files in that form exist on real machines. A bare number is still
 * read as a start time with no observation, so upgrading does not reset a timer that has been
 * running for days — losing it would put a wrong `elapsedSec` on the next record, and a wrong
 * number is worse than an absent one. New writes use the object form (#120).
 *
 * Reads are lenient in the same posture as protocol parsing. An entry that cannot be read is
 * dropped with a warning instead of failing the call: the cost of a lost start time is one
 * wrong `elapsedSec`, while the cost of throwing here is a capture that never happens — and a
 * grading Programmers has already broadcast is gone for good (protocol §11).
 */
class FileProblemTimer(private val file: AtomicStateFile, private val clock: Clock) : ProblemTimer {
    override fun elapsedSecOf(lessonId: Long): Long {
        val startedAt = states()[key(lessonId)]?.startedAt ?: return 0
        val elapsed = Duration.between(Instant.ofEpochSecond(startedAt), clock.instant())
        // A clock that moved backwards must not produce a negative duration in a record.
        return elapsed.seconds.coerceAtLeast(0)
    }

    /** Synchronized because this is a read-modify-write; the file guards readers, not writers. */
    @Synchronized
    override fun startIfAbsent(lessonId: Long) {
        val current = states()
        if (current.containsKey(key(lessonId))) return
        file.write(encode(current + (key(lessonId) to ProblemState(clock.instant().epochSecond))))
    }

    /**
     * Last-write-wins: the sensor sends cumulative values on every heartbeat, so the newest
     * reading is the whole answer rather than an increment.
     *
     * A report for a problem with no timer is ignored rather than starting one. The timer's
     * start is the moment the problem was first *announced*, and inventing it from a
     * telemetry message would put a measured-looking elapsed time on a problem nothing is
     * watching.
     */
    @Synchronized
    override fun observed(lessonId: Long, observation: SensorObservation) {
        val current = states()
        val existing = current[key(lessonId)] ?: return
        file.write(encode(current + (key(lessonId) to existing.copy(observation = observation))))
    }

    override fun observationOf(lessonId: Long): SensorObservation? = states()[key(lessonId)]?.observation

    private fun states(): Map<String, ProblemState> {
        val text = file.read() ?: return emptyMap()
        val document = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { return unreadable() }
        val entries = document.entries.mapNotNull { (lesson, value) -> stateOf(value)?.let { lesson to it } }
        warnDropped(document.size - entries.size)
        return entries.toMap()
    }

    private fun stateOf(value: JsonElement): ProblemState? = when (value) {
        // The pre-#120 shape: the lesson mapped straight to its epoch second.
        is JsonPrimitive -> value.longOrNull?.let { ProblemState(it) }
        is JsonObject -> objectState(value)
        else -> null
    }

    private fun objectState(entry: JsonObject): ProblemState? {
        val startedAt = (entry[STARTED_AT] as? JsonPrimitive)?.longOrNull ?: return null
        return ProblemState(
            startedAt = startedAt,
            observation = SensorObservation.ofReceived(
                focusedSec = (entry[FOCUSED_SEC] as? JsonPrimitive)?.longOrNull,
                sawQuestions = (entry[SAW_QUESTIONS] as? JsonPrimitive)?.booleanOrNull,
            ),
        )
    }

    private fun warnDropped(dropped: Int) {
        if (dropped <= 0) return
        logger.warn("Dropped {} unreadable entries from the timer document", dropped)
    }

    private fun unreadable(): Map<String, ProblemState> {
        logger.warn("Timer document at {} is unreadable; treating it as empty", file)
        return emptyMap()
    }

    // Sorted so the document has one canonical form and a diff shows only what actually changed.
    private fun encode(states: Map<String, ProblemState>): String {
        val document = buildJsonObject {
            states.toList().sortedBy { (lesson, _) -> lesson }.forEach { (lesson, state) ->
                put(lesson, entryOf(state))
            }
        }
        return Json.encodeToString(JsonObject.serializer(), document)
    }

    // Absent rather than false or zero when the sensor never reported: a reader has to be able
    // to tell "not seen" from "seen and it was nothing".
    private fun entryOf(state: ProblemState): JsonObject = buildJsonObject {
        put(STARTED_AT, state.startedAt)
        state.observation?.let {
            put(FOCUSED_SEC, it.focusedSec)
            put(SAW_QUESTIONS, it.sawQuestions)
        }
    }

    private fun key(lessonId: Long): String = lessonId.toString()

    companion object {
        private const val TIMERS = "timers.json"
        private const val STARTED_AT = "startedAt"
        private const val FOCUSED_SEC = "focusedSec"
        private const val SAW_QUESTIONS = "sawQuestions"

        private val logger = LoggerFactory.getLogger(FileProblemTimer::class.java)

        /** Timers live under the record repository, next to the records they describe (design §5.1). */
        fun under(recordRoot: Path, clock: Clock): FileProblemTimer =
            FileProblemTimer(AtomicStateFile.under(recordRoot, TIMERS), clock)
    }
}
