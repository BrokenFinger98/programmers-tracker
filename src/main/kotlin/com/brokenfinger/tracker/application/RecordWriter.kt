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
    private val examples: ExampleStore,
    private val attempts: AttemptAuthority,
    private val gaps: SubmissionGaps,
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
        val added = captured.add(key)
        // A write that never landed was never a capture, so a key this call introduced goes
        // back: a retry from the raw log must not be mistaken for a replay of a stored record.
        runCatching { record(capture, key) }.onFailure { if (added) captured.remove(key) }.getOrThrow()
    }

    /**
     * Records a grading **replayed from the raw log**, or returns null when its record already
     * exists.
     *
     * This is the only path that consults the dedup index, and #159 is why. The key was being
     * asked a question it cannot answer on the live path — *"is this the same grading?"* — when
     * all it can see is whether the bytes match. For SQL the bytes always match: its frames
     * carry no `run_time` and no `memory_size`, so the same query submitted twice is
     * byte-identical, and the second submission was dropped as a replay. Measured 2026-08-11 on
     * lesson 151136. Java escaped only because its timings jitter, which is luck.
     *
     * A live capture is not a replay — it is a thing that just happened, and the socket does
     * not redeliver: a reconnect loses whatever was broadcast meanwhile rather than repeating
     * it. The one way one grading used to reach the writer twice was two channels on one
     * problem, and #158 closed that at the subscription instead.
     *
     * Replay is different in kind. Reconciliation re-reads bytes already on disk, so matching
     * bytes there really do mean the same grading, and the index is exactly right.
     */
    suspend fun replay(capture: SettledCapture): SubmissionRecord? = withContext(writerDispatcher) {
        val key = capture.captureKey()
        if (!captured.add(key)) return@withContext duplicate(capture)
        runCatching { record(capture, key) }.onFailure { captured.remove(key) }.getOrThrow()
    }

    /**
     * Copy the frames, append the record, then retire the source (#95). `.ps/raw` is the
     * only directory the reconciler scans, so a move before the append meant a failed
     * append — a full disk, an unmounted record repo — took the grading out of the recovery
     * queue for good, while the log said its frames were kept. Copying leaves the source in
     * place until the record naming its destination is durable; an interruption anywhere
     * before [RawSessionLog.discard] therefore replays, and the capture key drops the
     * replay as the duplicate it is.
     */
    private fun record(capture: SettledCapture, key: CaptureKey): SubmissionRecord {
        val attempt = attempts.allocate(capture.lessonId, capture.action())
        val copied = copiedRawPath(capture, attempt)
        val at = OffsetDateTime.now(clock)
        // Inside the confined section like the attempt number, and for the same reason: the log
        // is the one authority for both, and a second reader would race the write (#207).
        val record = capture.toRecord(at, attempt, key, copied, gaps.sincePrevious(capture.lessonId, at))
        store.append(SubmissionRecordJson.encode(record))
        retireRaw(capture, copied != null)
        // After the append, inside the same confined section: the examples are a derived
        // write to the problem directory and must not interleave with another grading's
        // (write-serialization decision 1). The store itself is a no-op for a grading that
        // announced none, so a submit cannot blank what its preceding run wrote.
        examples.replace(capture.lessonId, capture.problem?.title, capture.session.examples)
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
        listOfNotNull(submissionLog, record.rawPath?.let { recordRoot.resolve(it) }).filter { Files.exists(it) }

    /**
     * Copies the frames to their resting place and answers where the record should point,
     * relative to the record repository.
     *
     * Best-effort by design: the verdict is unrecoverable and the copy is not, so a failed
     * copy leaves the path naming the raw directory — where the frames still are — rather
     * than a tidier path that would be a lie.
     */
    private fun copiedRawPath(capture: SettledCapture, attempt: Int): String? {
        if (!capture.movesRaw(attempt)) return null
        val destination = rawAttemptPath.of(capture.lessonId, capture.problem?.title, attempt)
        return runCatching { relativeOf(rawLog.complete(capture.rawSessionId, destination)) }
            .onFailure { logger.warn("Raw frames stayed in the raw directory ({})", it.javaClass.simpleName) }
            .getOrNull()
    }

    /**
     * Takes the source off the work list once the record is durable, either way — leaving it
     * there made every boot re-read every run ever captured (#99).
     *
     * A copy exists only for a submit, so only then may the source be deleted. A run's
     * frames are the sole original that grading will ever have, so they are set aside
     * instead: preserved outside the record repository, and invisible to the reconciler
     * that could never settle them into a second record anyway.
     *
     * A failure here costs a stale file the reconciler replays and the capture key drops,
     * which is the safe direction.
     */
    private fun retireRaw(capture: SettledCapture, copied: Boolean) {
        runCatching { if (copied) rawLog.discard(capture.rawSessionId) else rawLog.setAside(capture.rawSessionId) }
            .onFailure { logger.warn("Raw frames stayed on the work list ({})", it.javaClass.simpleName) }
    }

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
            examples: ExampleStore = ExampleStore { _, _, _ -> },
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
                examples = examples,
                submissionLog = submissionLog,
                attempts = AttemptAuthority.from(history),
                gaps = SubmissionGaps.from(history),
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
