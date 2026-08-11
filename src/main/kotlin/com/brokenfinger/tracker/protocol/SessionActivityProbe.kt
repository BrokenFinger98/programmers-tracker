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
 * It is deliberately not parsed. The status is the whole signal; reading the body would add a
 * second way to be wrong about a question the status already answers.
 */
class SessionActivityProbe(
    private val pages: PageSource,
    private val base: String = DEFAULT_BASE,
    private val year: () -> Int = { java.time.Year.now().value },
) : SessionProbe {
    override suspend fun probe(): SessionState = runCatching { stateOf(pages.get(url()).status) }
        .getOrElse {
            // A probe that could not run says nothing about the cookie. Reporting EXPIRED
            // here would tell the user to replace a credential that is probably fine.
            logger.debug("Session probe could not reach Programmers ({})", it.javaClass.simpleName)
            SessionState.UNKNOWN
        }

    private fun stateOf(status: Int): SessionState = when (status) {
        OK -> SessionState.ALIVE
        UNAUTHORIZED -> expired()
        else -> SessionState.UNKNOWN
    }

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
