package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.cable.CableChannelSubscriber
import com.brokenfinger.tracker.adapter.store.AtomicStateFile
import com.brokenfinger.tracker.adapter.store.FileProblemTimer
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.JsonlRecordStore
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.ProblemTimer
import com.brokenfinger.tracker.application.RawSessionLog
import com.brokenfinger.tracker.application.RecordWriter
import com.brokenfinger.tracker.application.SubscriptionRegistry
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.CableEndpoint
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.ManualFileSessionProvider
import com.brokenfinger.tracker.protocol.SessionProvider
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

    @Bean
    fun rawSessionLog(@Value("\${tracker.raw-dir}") rawDir: String, clock: Clock): RawSessionLog =
        FileRawSessionLog(Path.of(rawDir), clock)

    @Bean
    fun problemTimer(@Value("\${tracker.timers-file}") timersFile: String, clock: Clock): ProblemTimer =
        FileProblemTimer(AtomicStateFile(Path.of(timersFile)), clock)

    @Bean
    fun recordWriter(
        layout: RecordLayout,
        rawLog: RawSessionLog,
        @Value("\${tracker.record-repo}") recordRepo: String,
        clock: Clock,
    ): RecordWriter = RecordWriter.of(
        store = JsonlRecordStore(layout.submissionLog()),
        rawLog = rawLog,
        rawAttemptPath = layout::rawAttemptFile,
        recordRoot = recordRoot(recordRepo),
        clock = clock,
    )

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
    ): ChannelSubscriber = CableChannelSubscriber(client, sessions, scope.scope) { channel: ChannelIdentifier ->
        ChannelCapture(channel, rawLog, registry, writer, timer)
    }

    private fun recordRoot(recordRepo: String): Path =
        Path.of(recordRepo.replaceFirst("~", System.getProperty("user.home")))
}

/** Wraps the observation scope so Spring can cancel it on shutdown rather than leaking jobs. */
class CaptureScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("capture"))

    fun cancelScope() = scope.cancel()
}
