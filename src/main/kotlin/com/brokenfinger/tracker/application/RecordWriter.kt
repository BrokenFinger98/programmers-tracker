package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.CaptureKey
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.SubmissionRecordJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 2 of the capture pipeline — the confined single writer
 * ([[decisions/2026-08-05-write-serialization]] decision 1).
 *
 * Every derived write runs inside `withContext(writerDispatcher)` on a single-parallelism
 * dispatcher, so dedup, attempt allocation and the append form one indivisible section
 * without a lock, a consumer coroutine or a drain protocol. Callers stay on their own
 * per-session coroutines: the socket read loop is never the thread doing the serializing,
 * and a write that fails fails in the session that caused it.
 *
 * What it deliberately does **not** do is fetch code. That is stage 3 over the network, it
 * fails for reasons of its own, and a record whose verdict can never be recaptured must not
 * wait on it ([[decisions/2026-08-05-capture-pipeline-stages]]). Records therefore start with
 * `codePending`.
 *
 * The **commit** does run here, on this dispatcher, and that is deliberate
 * ([[decisions/2026-08-06-wire-git-into-the-pipeline]]): the record and its history are the
 * same derived write, and git has one index, so putting the commit anywhere else would put a
 * second writer next to the one this class exists to be. Stage 3's semantics are untouched —
 * a git failure is logged where it happened and left for the next reconciliation, never
 * raised here.
 */
class RecordWriter private constructor(
    private val store: RecordStore,
    private val rawLog: RawSessionLog,
    private val rawAttemptPath: AttemptRawPath,
    private val recordRoot: Path,
    private val git: GitSync,
    private val submissionLog: Path,
    private val attempts: AttemptAuthority,
    private val captured: MutableSet<CaptureKey>,
    private val clock: Clock,
    private val writerDispatcher: CoroutineDispatcher,
) {
    /**
     * Records one settled grading and returns it, or null when it was a duplicate an
     * earlier capture already recorded.
     *
     * Suspends only for as long as the writer is busy with someone else's grading, which is
     * bounded by one human's solving rate.
     */
    suspend fun write(capture: SettledCapture): SubmissionRecord? = withContext(writerDispatcher) {
        val key = capture.captureKey()
        if (!captured.add(key)) return@withContext duplicate(capture)
        // A write that never landed was never a capture, so the key goes back: a retry from
        // the raw log must not be mistaken for the reconnect replay this index exists to drop.
        runCatching { record(capture, key) }.onFailure { captured.remove(key) }.getOrThrow()
    }

    private fun record(capture: SettledCapture, key: CaptureKey): SubmissionRecord {
        val attempt = attempts.allocate(capture.lessonId, capture.action())
        val record = capture.toRecord(OffsetDateTime.now(clock), attempt, key, rawPathOf(capture, attempt))
        store.append(SubmissionRecordJson.encode(record))
        committed(record)
        return record
    }

    /**
     * Commits the record that was just appended — one submit, one commit, and a push when it
     * passed (design §4.6). The port reports failure rather than raising it, so nothing here
     * branches on the result: whatever this call could not commit is left for the next
     * reconciliation, and the log line explaining why was written where it happened.
     *
     * **The pass is the trigger, never the scope.** `git push` moves the whole branch, so
     * passing one problem also sends up every commit pending for every other one. Read this as
     * "records go up when something passes", not as "this problem's records go up".
     *
     * The guard is for a port that breaks its own no-throw contract. It has to be caught here
     * rather than by the caller: the record is already durable at this point, so letting it
     * escape would take the dedup key down with it and let a replay record the grading twice.
     */
    private fun committed(record: SubmissionRecord) {
        runCatching { git.commitSubmission(record, pathsOf(record)) }
            .onFailure { logger.warn("Lesson {} was recorded but not committed", record.lessonId, it) }
    }

    /**
     * What this record consists of on disk. A path git cannot match fails the whole partial
     * commit, so a raw file that never moved out of the raw directory — the move is
     * best-effort — is left out rather than taking the commit down with it.
     */
    private fun pathsOf(record: SubmissionRecord): List<Path> =
        listOf(submissionLog, recordRoot.resolve(record.rawPath)).filter { Files.exists(it) }

    /** Where this grading's frames came to rest, relative to the record repository. */
    private fun rawPathOf(capture: SettledCapture, attempt: Int): String {
        if (!capture.movesRaw(attempt)) return capture.rawSessionId.value
        val destination = rawAttemptPath.of(capture.lessonId, capture.problem?.title, attempt)
        return moved(capture, destination) ?: capture.rawSessionId.value
    }

    // Best-effort by design: the verdict is unrecoverable and the move is not, so a failed
    // move leaves the frames where they already are and costs a tidier path, never a record.
    private fun moved(capture: SettledCapture, destination: Path): String? =
        runCatching { relativeOf(rawLog.complete(capture.rawSessionId, destination)) }
            .onFailure { logger.warn("Raw frames stayed in the raw directory ({})", it.javaClass.simpleName) }
            .getOrNull()

    // Stored with forward slashes whatever the host uses — a record repository is meant to
    // be cloned onto another machine, and an absolute path would not survive the trip.
    private fun relativeOf(path: Path): String =
        recordRoot.toAbsolutePath().relativize(path.toAbsolutePath()).joinToString("/")

    // Never logs the key or the frames — a capture carries a learner's solving history.
    private fun duplicate(capture: SettledCapture): SubmissionRecord? {
        logger.info("Dropped a replayed capture of lesson {} — already recorded", capture.lessonId)
        return null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RecordWriter::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Opens the writer over an existing record repository, restoring **both** in-memory
         * indexes from `log/submissions.jsonl` — the attempt counter and the dedup keys.
         * The log is the single authority for each, so a restart continues the numbering it
         * finds there rather than rebuilding it from a directory scan (decisions 2 and 6).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun of(
            store: RecordStore,
            rawLog: RawSessionLog,
            rawAttemptPath: AttemptRawPath,
            recordRoot: Path,
            git: GitSync,
            submissionLog: Path,
            clock: Clock = Clock.systemDefaultZone(),
            writerDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
        ): RecordWriter {
            val history = store.read()
            return RecordWriter(
                store = store,
                rawLog = rawLog,
                rawAttemptPath = rawAttemptPath,
                recordRoot = recordRoot,
                git = git,
                submissionLog = submissionLog,
                attempts = AttemptAuthority.from(history),
                captured = keysOf(history),
                clock = clock,
                writerDispatcher = writerDispatcher,
            )
        }

        /**
         * The keys already recorded. Read straight from the stored line rather than from the
         * projection, because the projection deliberately carries only what the counter needs
         * and duplicating the record schema there would split it in two.
         */
        private fun keysOf(history: List<RecordedSubmission>): MutableSet<CaptureKey> {
            val keys = ConcurrentHashMap.newKeySet<CaptureKey>()
            history.mapNotNullTo(keys) { keyOf(it.line) }
            return keys
        }

        // Lenient (dev rules §4): a line whose key we cannot read costs one dedup entry, and
        // losing the whole index — every later record numbered as if the log were empty —
        // would cost far more.
        private fun keyOf(line: String): CaptureKey? = CaptureKey.ofReceived(
            runCatching { json.parseToJsonElement(line).jsonObject[KEY_FIELD]?.jsonPrimitive?.contentOrNull }
                .getOrNull(),
        )

        private const val KEY_FIELD = "captureKey"
    }
}

/**
 * Where a completed submit's frames belong — `attempts/NNN.raw.jsonl` under the problem's
 * directory (design §5.1).
 *
 * It is a port rather than a direct call because the naming lives in the store adapter and
 * `application` does not import `adapter` (dev rules §1). The composition root satisfies it
 * with `RecordLayout::rawAttemptFile`.
 */
fun interface AttemptRawPath {
    fun of(lessonId: Long, title: String?, attempt: Int): Path
}
