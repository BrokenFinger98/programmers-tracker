package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.ConnectionLiveness
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.SubscriptionHealth
import com.brokenfinger.tracker.protocol.ActionCableClient
import com.brokenfinger.tracker.protocol.CableEvent
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.SessionProvider
import com.brokenfinger.tracker.protocol.SubscriptionRejectedException
import com.brokenfinger.tracker.protocol.parse.ObservedFrames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
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
    private val health = ConcurrentHashMap<ChannelKey, SubscriptionHealth>()

    override fun subscribe(channel: ChannelKey) {
        jobs.computeIfAbsent(channel) {
            health[it] = SubscriptionHealth.PENDING
            observe(it)
        }
    }

    /**
     * Stops watching, which is an **ordinary** thing to do: it fires on every language switch
     * and on every closed tab.
     *
     * That mattered because the cancellation used to be caught as a failure, and one swallowed
     * exception cost three separate things (#217, measured across seven language switches on
     * 2026-08-12):
     *
     * 1. a WARN per switch saying *anything broadcast meanwhile is lost*, about a stop we asked
     *    for — and it shares a log with the reconnect warnings that do mean something
     * 2. an `UNREACHABLE` written back into [health] **after** the line below removed it,
     *    leaving an entry for a channel nobody watches
     * 3. one more pass of the retry loop, which called `connectionLost()` and settled any
     *    grading still in flight as INCOMPLETE, logging it as *dropped mid-grading*
     *
     * All three came from `runCatching` in [collectOnce] treating cancellation as an error, so
     * all three are fixed by rethrowing it there. Cancellation is not a failure; it is this
     * method working.
     */
    override fun unsubscribe(channel: ChannelKey) {
        jobs.remove(channel)?.cancel()
        health.remove(channel)
        logger.info("Stopped observing lesson {}", channel.lessonId.value)
    }

    /**
     * Absent means UNREACHABLE, not PENDING. A channel this class holds no job for is not
     * being watched, and answering optimistically is the defect #167 exists to remove.
     */
    override fun healthOf(channel: ChannelKey): SubscriptionHealth = health[channel] ?: SubscriptionHealth.UNREACHABLE

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

    /**
     * Returns whether any frame arrived, which is what makes the next wait a first attempt.
     *
     * The cable event becomes an `ObservedFrame` here, at the outermost edge: the capture is
     * `application` and never names a wire type
     * ([[decisions/2026-08-05-protocol-dependency-direction]] decision 2).
     */
    private suspend fun collectOnce(channel: ChannelKey, capture: ChannelCapture): Boolean {
        var received = false
        runCatching {
            client.observe(ChannelIdentifier.from(channel), sessions)
                .onEach {
                    received = true
                    // A heartbeat proves the subscription as well as a broadcast does — it is
                    // the only traffic an idle channel produces, so waiting for a grading to
                    // call a channel live would call every quiet channel broken.
                    health[channel] = SubscriptionHealth.LIVE
                }
                // The deadline sits ABOVE the filter on purpose (#94): the heartbeat is the
                // only traffic an idle channel produces, so it must reach the timeout — and
                // it must not reach the capture, which records what it is given.
                .timeout(silenceDeadline.toKotlinDuration())
                .filter { it !is CableEvent.Heartbeat }
                .collect { capture.onFrame(ObservedFrames.of(it)) }
        }.onFailure {
            // `runCatching` catches CancellationException like anything else, and for OUR OWN
            // cancellation that is never right — see [unsubscribe] for the three things it was
            // costing (#217).
            //
            // **The test is `isActive`, not the exception type.** `Flow.timeout()` reports the
            // silence deadline by throwing `TimeoutCancellationException`, which is also a
            // `CancellationException` — rethrowing on type alone passes the deadline straight
            // through the retry loop and disables the reconnect this class exists for. The
            // existing deadline test caught exactly that. What separates them is whether the
            // job itself was cancelled: a timeout leaves it active, `unsubscribe` does not.
            if (it is CancellationException && !currentCoroutineContext().isActive) throw it
            health[channel] = healthAfter(it)
            report(channel, it)
        }
        // A flow that completed without ever emitting proved nothing — that is the ~30-minute
        // silent close, which throws nothing. Left alone it would keep an earlier LIVE on
        // record for a channel that is no longer connected.
        if (!received) health.computeIfPresent(channel) { _, current -> notLive(current) }
        return received
    }

    /**
     * A health state is **not** reset per attempt, only demoted. The retry loop runs
     * continuously, so re-marking PENDING at the top of each attempt would make a refusal
     * blink out of view every second and defeat the point of tracking it at all. Only a frame
     * arriving clears a failure.
     *
     * PENDING is demoted here too. It means "subscribed a moment ago, give it a second", and
     * once a whole attempt has come and gone with nothing on it that excuse is spent —
     * leaving it would let a socket that opens and closes emitting nothing read as healthy
     * forever, which is the shape of the original defect.
     */
    private fun notLive(current: SubscriptionHealth): SubscriptionHealth =
        if (current == SubscriptionHealth.REJECTED) current else SubscriptionHealth.UNREACHABLE

    /**
     * A refusal and a broken socket are different answers to "why is nothing arriving", and
     * they ask the user for different things — a fresh cookie, or patience. Retrying a
     * refusal with the same credential cannot succeed, which is what makes the distinction
     * worth carrying all the way to the badge.
     */
    private fun healthAfter(cause: Throwable): SubscriptionHealth = when (cause) {
        is SubscriptionRejectedException -> SubscriptionHealth.REJECTED
        else -> SubscriptionHealth.UNREACHABLE
    }

    // Logged loudly and per occurrence: a silent gap is the failure this class exists to
    // prevent, so a reconnect must never look like ordinary operation.
    //
    // The message is included, not just the class name. A rejection carries the one sentence
    // that says what to do about it, and reducing it to `SubscriptionRejectedException` made
    // an expired cookie indistinguishable from a flaky network (#167).
    private fun report(channel: ChannelKey, cause: Throwable) {
        logger.warn(
            "Observation of lesson {} ended ({}: {}) — reconnecting; anything broadcast meanwhile is lost",
            channel.lessonId.value,
            cause.javaClass.simpleName,
            cause.message ?: "no detail",
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CableChannelSubscriber::class.java)
    }
}
