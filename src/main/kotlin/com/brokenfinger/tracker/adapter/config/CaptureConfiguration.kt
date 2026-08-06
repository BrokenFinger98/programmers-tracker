package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.cable.CableChannelSubscriber
import com.brokenfinger.tracker.adapter.catalog.ClasspathProblemCatalog
import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import com.brokenfinger.tracker.adapter.store.FileDerivedArtifacts
import com.brokenfinger.tracker.adapter.store.FileProblemTimer
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.CodeAttachment
import com.brokenfinger.tracker.application.CodeFetch
import com.brokenfinger.tracker.application.CodeFetcher
import com.brokenfinger.tracker.application.DailyBackup
import com.brokenfinger.tracker.application.DerivedArtifacts
import com.brokenfinger.tracker.application.FrameReader
import com.brokenfinger.tracker.application.GitSync
import com.brokenfinger.tracker.application.ProblemCatalog
import com.brokenfinger.tracker.application.ProblemTimer
import com.brokenfinger.tracker.application.RawSessionLog
import com.brokenfinger.tracker.application.RawSessionReconciler
import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.application.RecordWriter
import com.brokenfinger.tracker.application.StartupReconciliation
import com.brokenfinger.tracker.application.SubscriptionRegistry
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.CableEndpoint
import com.brokenfinger.tracker.protocol.KtorPageSource
import com.brokenfinger.tracker.protocol.ManualFileSessionProvider
import com.brokenfinger.tracker.protocol.PageSource
import com.brokenfinger.tracker.protocol.ProblemPageCodeFetcher
import com.brokenfinger.tracker.protocol.SessionProvider
import com.brokenfinger.tracker.protocol.parse.ObservedFrames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import java.nio.file.Path
import java.time.Clock

/**
 * Assembles the capture path: a watched channel gets a live subscription whose frames run
 * through [ChannelCapture] into a durable record.
 *
 * Everything here is construction. The decisions live in the classes being constructed —
 * this file only chooses which implementations and where on disk they write.
 */
@Configuration
class CaptureConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    fun cableEndpoint(@Value("\${tracker.cable.url}") url: String, @Value("\${tracker.cable.origin}") origin: String) =
        CableEndpoint(url, origin)

    @Bean
    fun sessionProvider(@Value("\${tracker.session-file}") sessionFile: String): SessionProvider =
        ManualFileSessionProvider(Path.of(sessionFile))

    @Bean
    fun actionCableClient(endpoint: CableEndpoint) = ActionCableClient(endpoint)

    @Bean
    fun recordLayout(@Value("\${tracker.record-repo}") recordRepo: String) = RecordLayout(recordRoot(recordRepo))

    /**
     * Read once at boot and held for the process lifetime — it is a few hundred kilobytes of
     * immutable reference data inside the jar, so re-reading it per lookup would buy nothing.
     * A singleton is also the whole failure mode: a jar built without the resource fails here,
     * loudly, rather than answering "unknown problem" forever.
     */
    @Bean
    fun problemCatalog(): ProblemCatalog = ClasspathProblemCatalog.load()

    @Bean
    fun recordStore(layout: RecordLayout): RecordStore = JsonlRecordStore(layout.submissionLog())

    /**
     * The one thread every derived write is confined to
     * ([[decisions/2026-08-05-write-serialization]] decision 1). Stage 2 and stage 3 must be
     * handed **this** instance and no other: two single-parallelism dispatchers serialize
     * perfectly well against themselves and not at all against each other.
     */
    @Bean
    @OptIn(ExperimentalCoroutinesApi::class)
    fun writerDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Bean
    fun rawSessionLog(@Value("\${tracker.raw-dir}") rawDir: String, clock: Clock): RawSessionLog =
        FileRawSessionLog(Path.of(rawDir), clock)

    @Bean
    fun problemTimer(@Value("\${tracker.timers-file}") timersFile: String, clock: Clock): ProblemTimer =
        FileProblemTimer(AtomicStateFile(Path.of(timersFile)), clock)

    /**
     * Depends on the lock by name rather than by argument: this is the first bean that reads
     * the record repository (it restores the attempt counter from `submissions.jsonl`), and
     * a writer built for a repository we were refused should never exist at all.
     */
    @Bean
    @DependsOn(RecordRepositoryConfiguration.LOCK_BEAN)
    fun recordWriter(
        layout: RecordLayout,
        store: RecordStore,
        rawLog: RawSessionLog,
        git: GitSync,
        @Value("\${tracker.record-repo}") recordRepo: String,
        clock: Clock,
        writerDispatcher: CoroutineDispatcher,
    ): RecordWriter = RecordWriter.of(
        store = store,
        rawLog = rawLog,
        rawAttemptPath = layout::rawAttemptFile,
        recordRoot = recordRoot(recordRepo),
        git = git,
        submissionLog = layout.submissionLog(),
        clock = clock,
        writerDispatcher = writerDispatcher,
    )

    @Bean
    fun startupReconciliation(
        sessions: RawSessionReconciler,
        attachment: CodeAttachment,
        git: GitSync,
        backup: DailyBackup,
    ) = StartupReconciliation(sessions, attachment, git, backup)

    /**
     * Picks up whatever an earlier run left behind — orphaned raw sessions, records still
     * waiting for their code, uncommitted work, and a backup the machine slept through. Runs
     * once at boot; every step is idempotent, so a boot that had nothing to recover does
     * nothing.
     */
    @Bean
    fun reconcileAtStartup(startup: StartupReconciliation) = ApplicationRunner { runBlocking { startup.run() } }

    @Bean(destroyMethod = "close")
    fun pageSource(sessions: SessionProvider): KtorPageSource = KtorPageSource { sessions.cookie().headerValue() }

    /**
     * The cookie is read per fetch, never at boot. The server must start with no session file
     * at all (dev rules §9.2), and a missing one reports as [CodeFetch.Unauthenticated] — which
     * is what it is — rather than as a page that happened to be missing.
     */
    @Bean
    fun codeFetcher(
        sessions: SessionProvider,
        pages: PageSource,
        @Value("\${tracker.page-base}") pageBase: String,
    ): CodeFetcher = CodeFetcher { lessonId, language -> fetched(sessions, pages, pageBase, lessonId, language) }

    @Bean
    fun derivedArtifacts(store: RecordStore, @Value("\${tracker.record-repo}") recordRepo: String): DerivedArtifacts =
        FileDerivedArtifacts(recordRoot(recordRepo), store)

    @Bean
    fun codeAttachment(
        fetcher: CodeFetcher,
        store: RecordStore,
        artifacts: DerivedArtifacts,
        writerDispatcher: CoroutineDispatcher,
    ) = CodeAttachment(fetcher, store, artifacts, writerDispatcher)

    private suspend fun fetched(
        sessions: SessionProvider,
        pages: PageSource,
        pageBase: String,
        lessonId: Long,
        language: String,
    ): CodeFetch {
        val cookie = runCatching { sessions.cookie() }.getOrNull() ?: return CodeFetch.Unauthenticated
        return ProblemPageCodeFetcher(cookie, pageBase, pages).fetch(lessonId, language)
    }

    /** The one crossing out of the wire format, shared by the live path and the replay. */
    @Bean
    fun frameReader(): FrameReader = ObservedFrames

    @Bean
    fun rawSessionReconciler(rawLog: RawSessionLog, writer: RecordWriter, timer: ProblemTimer, frames: FrameReader) =
        RawSessionReconciler(rawLog, writer, timer, frames)

    /**
     * Observation runs on a supervisor job so one channel's failure cannot cancel the
     * others — every grading the survivors would have seen is unrecoverable (protocol §11).
     */
    @Bean(destroyMethod = "cancelScope")
    fun captureScope() = CaptureScope()

    @Bean
    fun channelSubscriber(
        client: ActionCableClient,
        sessions: SessionProvider,
        scope: CaptureScope,
        registry: SubscriptionRegistry,
        rawLog: RawSessionLog,
        writer: RecordWriter,
        timer: ProblemTimer,
        attachment: CodeAttachment,
    ): ChannelSubscriber = CableChannelSubscriber(
        client = client,
        sessions = sessions,
        scope = scope.scope,
        captureFor = { channel: ChannelKey -> ChannelCapture(channel, rawLog, registry, writer, timer, attachment) },
    )

    private fun recordRoot(recordRepo: String): Path = ConfiguredPath.of(recordRepo)
}

/** Wraps the observation scope so Spring can cancel it on shutdown rather than leaking jobs. */
class CaptureScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("capture"))

    fun cancelScope() = scope.cancel()
}
