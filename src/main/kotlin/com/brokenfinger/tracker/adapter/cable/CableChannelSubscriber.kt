package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.ConnectionLiveness
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.SessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

/**
 * Holds one live subscription per watched channel and feeds its frames to a [ChannelCapture].
 *
 * One socket per channel rather than one multiplexed connection: protocol doc §10 measured
 * two sockets receiving identical broadcasts, so this shape is known to work, while
 * multiplexing is a decision that would need evidence of its own. The registry caps watched
 * channels at 8 (design §4.1), so the socket count is bounded by construction.
 *
 * **Observation is retried until the channel is unsubscribed.** An idle socket was measured
 * closing silently after ~30 minutes — no exception, no close frame — and everything
 * broadcast after that point is lost forever with nothing in the logs to say a gap existed
 * (protocol doc §11). Silence is therefore treated as failure, not as calm.
 */
class CableChannelSubscriber(
    private val client: ActionCableClient,
    private val sessions: SessionProvider,
    private val scope: CoroutineScope,
    private val silenceDeadline: Duration = ConnectionLiveness.DEFAULT_DEADLINE,
    private val waitFor: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    private val captureFor: (ChannelKey) -> ChannelCapture,
) : ChannelSubscriber {
    private val jobs = ConcurrentHashMap<ChannelKey, Job>()

    override fun subscribe(channel: ChannelKey) {
        jobs.computeIfAbsent(channel) { observe(it) }
    }

    override fun unsubscribe(channel: ChannelKey) {
        jobs.remove(channel)?.cancel()
        logger.info("Stopped observing lesson {}", channel.lessonId.value)
    }

    private fun observe(channel: ChannelKey): Job {
        val capture = captureFor(channel)
        return scope.launch { observeUntilCancelled(channel, capture) }
    }

    private suspend fun observeUntilCancelled(channel: ChannelKey, capture: ChannelCapture) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt = if (collectOnce(channel, capture)) 1 else attempt + 1
            capture.connectionLost()
            waitFor(ConnectionLiveness.retryDelayFor(attempt))
        }
    }

    /** Returns whether any frame arrived, which is what makes the next wait a first attempt. */
    private suspend fun collectOnce(channel: ChannelKey, capture: ChannelCapture): Boolean {
        var received = false
        runCatching {
            client.observe(ChannelIdentifier.from(channel), sessions)
                .onEach { received = true }
                .timeout(silenceDeadline.toKotlinDuration())
                .collect { capture.onEvent(it) }
        }.onFailure { report(channel, it) }
        return received
    }

    // Logged loudly and per occurrence: a silent gap is the failure this class exists to
    // prevent, so a reconnect must never look like ordinary operation.
    private fun report(channel: ChannelKey, cause: Throwable) {
        logger.warn(
            "Observation of lesson {} ended ({}) — reconnecting; anything broadcast meanwhile is lost",
            channel.lessonId.value,
            cause.javaClass.simpleName,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CableChannelSubscriber::class.java)
    }
}
