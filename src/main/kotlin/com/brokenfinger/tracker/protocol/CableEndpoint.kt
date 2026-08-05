package com.brokenfinger.tracker.protocol

/**
 * Where the cable lives. Defaults are measured protocol facts (protocol doc §1, §10);
 * both are overridable because hardcoded environments are forbidden (dev rules §9.1).
 */
data class CableEndpoint(val url: String, val origin: String) {
    companion object {
        const val DEFAULT_URL = "wss://ws.programmers.co.kr:443/cable"
        const val DEFAULT_ORIGIN = "https://school.programmers.co.kr"

        fun fromEnvironment(env: (String) -> String? = System::getenv): CableEndpoint = CableEndpoint(
            url = env("TRACKER_CABLE_URL") ?: DEFAULT_URL,
            origin = env("TRACKER_CABLE_ORIGIN") ?: DEFAULT_ORIGIN,
        )
    }
}
