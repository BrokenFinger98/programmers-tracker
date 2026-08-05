package com.brokenfinger.tracker.protocol

/**
 * The HTTP boundary of a page fetch, isolated the same way [RawSocket] isolates the socket:
 * everything above it is testable without a server, and the Ktor types stay in one place.
 */
data class PageResponse(val status: Int, val body: String, val location: String? = null)

fun interface PageSource {
    suspend fun get(url: String): PageResponse
}
