package com.brokenfinger.tracker.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Text-frame socket boundary — the only seam the WebSocket library touches, so the
 * subscription loop stays testable against captures without a live connection.
 */
interface RawSocket {
    /** Next text frame, or null once the socket is closed. */
    suspend fun receive(): String?

    suspend fun send(text: String)
}

class SubscriptionRejectedException(reason: String) : RuntimeException(reason)

/** Drives [SubscriptionProtocol] over this socket until it closes. */
fun RawSocket.events(protocol: SubscriptionProtocol): Flow<CableEvent> = flow {
    while (true) {
        val text = receive() ?: break
        execute(this@events, protocol.next(text))
    }
}

private suspend fun FlowCollector<CableEvent>.execute(socket: RawSocket, step: SubscriptionProtocol.Step) =
    when (step) {
        is SubscriptionProtocol.Step.Send -> socket.send(step.frameText)
        is SubscriptionProtocol.Step.Emit -> emit(step.event)
        is SubscriptionProtocol.Step.Fail -> throw SubscriptionRejectedException(step.reason)
        SubscriptionProtocol.Step.Ignore -> Unit
    }
