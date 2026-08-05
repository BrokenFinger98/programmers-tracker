package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.SessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds one live subscription per watched channel and feeds its frames to a [ChannelCapture].
 *
 * One socket per identifier rather than one multiplexed connection: protocol doc §10 measured
 * two sockets receiving identical broadcasts, so this shape is known to work, while
 * multiplexing is a decision that would need evidence of its own. The registry caps watched
 * channels at 8 (design §4.1), so the socket count is bounded by construction.
 *
 * A collection failure ends only that channel's job. Losing one subscription must not take
 * the others down with it — every grading they would have observed is unrecoverable
 * (protocol doc §11).
 */
class CableChannelSubscriber(
    private val client: ActionCableClient,
    private val sessions: SessionProvider,
    private val scope: CoroutineScope,
    private val captureFor: (ChannelIdentifier) -> ChannelCapture,
) : ChannelSubscriber {
    private val jobs = ConcurrentHashMap<ChannelIdentifier, Job>()

    override fun subscribe(identifier: ChannelIdentifier) {
        jobs.computeIfAbsent(identifier) { observe(it) }
    }

    override fun unsubscribe(identifier: ChannelIdentifier) {
        jobs.remove(identifier)?.cancel()
        logger.info("Stopped observing lesson {}", identifier.lessonId.value)
    }

    private fun observe(identifier: ChannelIdentifier): Job {
        val capture = captureFor(identifier)
        return scope.launch {
            client.observe(identifier, sessions)
                .catch { logger.warn("Observation of lesson {} ended: {}", identifier.lessonId.value, it.message) }
                .collect { capture.onEvent(it) }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CableChannelSubscriber::class.java)
    }
}
