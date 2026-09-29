package com.brokenfinger.tracker.adapter.cable

import com.brokenfinger.tracker.application.ChannelCapture
import com.brokenfinger.tracker.application.ChannelSubscriber
import com.brokenfinger.tracker.application.ConnectionLiveness
import com.brokenfinger.tracker.application.CredentialCheck
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val closePatience: Duration = DEFAULT_CLOSE_PATIENCE,
    private val captureFor: (ChannelKey) -> ChannelCapture,
) : ChannelSubscriber {
    /**
     * Everything one channel's observation owns, in one object: its job, what it has proved so
     * far, and the digest of the credential it connected with (#331).
     *
     * One object rather than three maps, because the review of #331 found the seam between
     * them: a job already replaced kept writing its health under the channel's *name*, and a
     * reopen read that as "still wanted" and brought a stopped channel back. A job now writes
     * only to its own observation. Whether that object is still the one [held] for the channel
     * is decided under [lock], and nowhere else.
     */
    private class Observation(val channel: ChannelKey, val capture: ChannelCapture) {
        @Volatile var job: Job? = null

        @Volatile var health: SubscriptionHealth = SubscriptionHealth.PENDING

        /** The digest of the credential this observation connected with — never the value. */
        @Volatile var openedWith: String? = null
    }

    private val held = ConcurrentHashMap<ChannelKey, Observation>()

    /**
     * The digest the last heartbeat saw, for any channel. "The credential changed" is decided
     * against this, not against a socket: a socket that reconnected on its own already carries
     * the new one, and the cached session answer would otherwise outlive the replacement.
     */
    private var lastSeen: String? = null

    // Every change to what is held — a start, a stop, a reopen — happens under this lock, so a
    // stop and a reopen cannot interleave halfway through each other. Nothing suspends under it.
    private val lock = Any()

    override fun subscribe(channel: ChannelKey) {
        synchronized(lock) { held.computeIfAbsent(channel) { start(it) } }
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
     * 2. an `UNREACHABLE` written back **after** the line below removed the channel, leaving an
     *    entry for a channel nobody watches
     * 3. one more pass of the retry loop, which called `connectionLost()` and settled any
     *    grading still in flight as INCOMPLETE, logging it as *dropped mid-grading*
     *
     * All three came from `runCatching` in [collectOnce] treating cancellation as an error, so
     * all three are fixed by rethrowing it there. Cancellation is not a failure; it is this
     * method working.
     */
    override fun unsubscribe(channel: ChannelKey) {
        synchronized(lock) { held.remove(channel) }?.job?.cancel()
        logger.info("Stopped observing lesson {}", channel.lessonId.value)
    }

    /**
     * Absent means UNREACHABLE, not PENDING. A channel this class holds nothing for is not
     * being watched, and answering optimistically is the defect #167 exists to remove.
     */
    override fun healthOf(channel: ChannelKey): SubscriptionHealth =
        held[channel]?.health ?: SubscriptionHealth.UNREACHABLE

    override suspend fun reauthenticate(channel: ChannelKey): CredentialCheck {
        val current = currentFingerprint() ?: return CredentialCheck.UNCHANGED
        val (changed, stale, successor) = synchronized(lock) {
            val changed = lastSeen != null && lastSeen != current
            lastSeen = current
            val observation = held[channel] ?: return CredentialCheck(changed, reopened = false)
            val authenticatedWith = observation.openedWith ?: return CredentialCheck(changed, reopened = false)
            if (authenticatedWith == current) return CredentialCheck(changed, reopened = false)
            // The successor takes the channel's place before the old socket is closed. A second
            // heartbeat then finds nothing to reopen (no fingerprint yet), a subscribe finds the
            // channel held, and a stop removes it — which is how a stop wins, below.
            val next = Observation(channel, captureFor(channel))
            held[channel] = next
            Triple(changed, observation, next)
        }
        logger.info("The session credential changed — reopening the observation of lesson {}", channel.lessonId.value)
        try {
            closeBeforeReopening(stale)
        } finally {
            // Started whatever happened during the close: a heartbeat interrupted mid-join must
            // not leave the channel held by an observation that never connects, which the badge
            // would show as PENDING forever. Unless a stop arrived meanwhile — then the successor
            // is no longer held, and a stop stays a stop.
            synchronized(lock) {
                if (held[channel] === successor) successor.job = observe(successor)
            }
        }
        return CredentialCheck(changed, reopened = true)
    }

    /**
     * The old socket is closed before the new one opens — two collectors on one channel would
     * break [ChannelCapture]'s one-collector rule. The wait is bounded, because a heartbeat must
     * not hang on a close that is stuck behind, say, a push on the way out of a grading.
     *
     * After the patience runs out the successor opens while the old observation may still be
     * finishing its last frame. It cannot receive another: a flow's `emit` checks cancellation
     * (the kotlinx.coroutines contract for the `flow` builder [ActionCableClient.observe] uses),
     * so no frame reaches two captures. What can overlap is one capture finishing and another
     * starting, and that is the accepted cost, stated here rather than assumed away.
     */
    private suspend fun closeBeforeReopening(stale: Observation) {
        val job = stale.job ?: return
        job.cancel()
        if (withTimeoutOrNull(closePatience.toMillis()) { job.join() } != null) {
            // Closed by us, so the retry loop's own `connectionLost()` never ran for this attempt
            // (a cancellation is rethrown past it, #217). Without this a grading the old socket
            // held open stays pinned in the registry, un-evictable, with no log line — the review
            // of #331 measured a slot lost for good. Safe here and only here: the job is done, so
            // no collector is inside the capture.
            stale.capture.connectionLost()
            return
        }
        logger.warn(
            "The old observation of lesson {} did not close within {} — opening the new one anyway",
            stale.channel.lessonId.value,
            closePatience,
        )
    }

    /**
     * A credential that cannot be read right now says nothing about whether it changed. Tearing
     * down a working observation over a missing file would turn a typo into a lost grading.
     *
     * Read on the IO dispatcher: the heartbeat handler runs on a virtual thread, and file I/O
     * would pin its carrier.
     */
    private suspend fun currentFingerprint(): String? = try {
        withContext(Dispatchers.IO) { sessions.cookie().fingerprint() }
    } catch (cancelled: CancellationException) {
        throw cancelled // the caller's cancellation, never a "cannot tell"
    } catch (unreadable: Exception) {
        null
    }

    private fun start(channel: ChannelKey): Observation =
        Observation(channel, captureFor(channel)).also { it.job = observe(it) }

    private fun observe(me: Observation): Job = scope.launch { observeUntilCancelled(me) }

    private suspend fun observeUntilCancelled(me: Observation) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt = if (collectOnce(me)) 1 else attempt + 1
            me.capture.connectionLost()
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
    /**
     * `Flow.timeout` is `@FlowPreview`, and the annotation is the acceptance rather than a
     * suppression: a coroutines upgrade may rename or remove it (#310).
     *
     * The acceptance is affordable because **both halves of what it means are pinned by tests**,
     * not because a preview API in the critical path is fine. Removal is a compile failure and
     * cannot be missed. The failure worth worrying about is a silent change of meaning — from
     * *gap between emissions* to *total collection time* — and
     * `heartbeats hold the socket open and never reach the capture` runs a 200 ms deadline against
     * a flow that emits for 600 ms and asserts zero reconnects. That test goes red the moment the
     * meaning shifts.
     *
     * The stable alternative is not equivalent: `withTimeoutOrNull` bounds the whole collection,
     * which would end a healthy socket the first time a channel stayed open past the deadline. A
     * per-emission timer written by hand is the only other option, and it is more code in the one
     * path where being wrong means a silently dead subscription.
     */
    @OptIn(FlowPreview::class)
    private suspend fun collectOnce(me: Observation): Boolean {
        var received = false
        runCatching {
            // Read once per attempt and handed to the client as it is, so the digest remembered
            // here describes exactly the credential this socket connects with (#331).
            val cookie = sessions.cookie()
            me.openedWith = cookie.fingerprint()
            client.observe(ChannelIdentifier.from(me.channel), SessionProvider { cookie })
                .onEach {
                    received = true
                    // A heartbeat proves the subscription as well as a broadcast does — it is
                    // the only traffic an idle channel produces, so waiting for a grading to
                    // call a channel live would call every quiet channel broken.
                    me.health = SubscriptionHealth.LIVE
                }
                // The deadline sits ABOVE the filter on purpose (#94): the heartbeat is the
                // only traffic an idle channel produces, so it must reach the timeout — and
                // it must not reach the capture, which records what it is given.
                .timeout(silenceDeadline.toKotlinDuration())
                .filter { it !is CableEvent.Heartbeat }
                .collect { me.capture.onFrame(ObservedFrames.of(it)) }
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
            me.health = healthAfter(it)
            report(me.channel, it)
        }
        // A flow that completed without ever emitting proved nothing — that is the ~30-minute
        // silent close, which throws nothing. Left alone it would keep an earlier LIVE on
        // record for a channel that is no longer connected.
        if (!received) me.health = notLive(me.health)
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

        /** Chosen, not measured: a websocket close is milliseconds; five seconds is "something is wrong". */
        val DEFAULT_CLOSE_PATIENCE: Duration = Duration.ofSeconds(5)
    }
}
