package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.SessionProbe
import com.brokenfinger.tracker.domain.SessionState
import org.slf4j.LoggerFactory

/**
 * Asks Programmers whether the cookie still authenticates, using the one endpoint measured to
 * give an unambiguous answer (protocol doc §14, measured 2026-08-11 for #179).
 *
 * Four endpoints were compared with and without the cookie. The lesson page answers 200 either
 * way, `solution_groups` is problem-scoped and answers differently depending on whether the
 * problem is solved, and the challenges API answers 200 with an empty payload — an emptiness
 * indistinguishable from a user who has solved nothing. This one answers **200 authenticated,
 * 401 not**, in JSON both times, so it also avoids the 200-with-HTML throttling shape §14
 * records for the other API.
 *
 * The body is **shape-checked, not parsed**. The status would be the whole signal if a 200 meant
 * what it says, and protocol §14 records that for this API family it does not: throttling comes
 * back as **200 with an HTML error page** rather than 429, so a throttle would otherwise read as
 * "your session is fine" (#191). Whether *this* endpoint throttles that way is unmeasured — the
 * check is cheap and correct either way, and triggering a rate limit to find out would be
 * deliberately hammering Programmers to prove a property we can simply stop claiming
 * (development-rules §9.3).
 *
 * No field is read. Once the body is well-formed JSON the status does answer the question.
 */
class SessionActivityProbe(
    private val pages: PageSource,
    private val base: String = DEFAULT_BASE,
    private val year: () -> Int = { java.time.Year.now().value },
) : SessionProbe {
    override suspend fun probe(): SessionState = runCatching { pages.get(url()).let { stateOf(it.status, it.body) } }
        .getOrElse {
            // A probe that could not run says nothing about the cookie. Reporting EXPIRED
            // here would tell the user to replace a credential that is probably fine.
            logger.debug("Session probe could not reach Programmers ({})", it.javaClass.simpleName)
            SessionState.UNKNOWN
        }

    private fun stateOf(status: Int, body: String): SessionState = when {
        // An HTML error page served as 200 is not a session, and it is not an expired one
        // either — we simply did not get an answer.
        !looksLikeJson(body) -> SessionState.UNKNOWN
        status == OK -> SessionState.ALIVE
        status == UNAUTHORIZED -> expired()
        else -> SessionState.UNKNOWN
    }

    // A shape, not a parse: both measured answers are objects (protocol §15.4), and anything
    // that does not start like one is the error page rather than the API.
    private fun looksLikeJson(body: String): Boolean = body.trimStart().startsWith("{")

    private fun expired(): SessionState {
        logger.warn(
            "The Programmers session is no longer valid — nothing will be recorded until it is " +
                "replaced. The socket cannot see this (protocol doc §15.3), so it is checked here.",
        )
        return SessionState.EXPIRED
    }

    // No cookie is held here: the PageSource carries it, and a credential parameter this class
    // never reads would be surface for nothing.
    private fun url(): String = "$base$PATH?year=${year()}"

    private companion object {
        val logger = LoggerFactory.getLogger(SessionActivityProbe::class.java)
        const val DEFAULT_BASE = "https://school.programmers.co.kr"

        /** User-scoped and problem-independent, which is what makes it the right question. */
        const val PATH = "/api/v1/main/open-challenge-activities"
        const val OK = 200
        const val UNAUTHORIZED = 401
    }
}
