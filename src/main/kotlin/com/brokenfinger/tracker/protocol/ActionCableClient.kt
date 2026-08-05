package com.brokenfinger.tracker.protocol

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Connects to the Programmers cable and passively observes one channel subscription
 * (protocol doc §10). The Ktor session is wrapped as a [RawSocket] so everything above
 * this class is testable against captures; only this glue needs the live connection.
 */
class ActionCableClient(
    private val endpoint: CableEndpoint,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    fun observe(identifier: ChannelIdentifier, sessionProvider: SessionProvider): Flow<CableEvent> = flow {
        val cookie = sessionProvider.cookie()
        httpClient.webSocket(endpoint.url, request = { connectHeaders(cookie) }) {
            emitAll(KtorRawSocket(this).events(SubscriptionProtocol(identifier)))
        }
    }

    // Headers measured on the working separate-process connection (protocol doc §10).
    private fun HttpRequestBuilder.connectHeaders(cookie: SessionCookie) {
        header(HttpHeaders.Cookie, cookie.headerValue())
        header(HttpHeaders.Origin, endpoint.origin)
        header(HttpHeaders.SecWebSocketProtocol, "actioncable-v1-json")
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(WebSockets)
            // A timeout verdict measured 87 s (protocol doc §13.5); the observation
            // socket is long-lived, so the engine request timeout is disabled.
            engine { requestTimeout = 0 }
        }
    }
}

private class KtorRawSocket(private val session: DefaultClientWebSocketSession) : RawSocket {
    override suspend fun receive(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return frame.readText()
        }
    }

    override suspend fun send(text: String) = session.send(Frame.Text(text))
}
