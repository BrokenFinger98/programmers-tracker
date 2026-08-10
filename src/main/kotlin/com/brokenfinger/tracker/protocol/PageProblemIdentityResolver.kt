package com.brokenfinger.tracker.protocol

import com.brokenfinger.tracker.application.ProblemIdentityResolver
import com.brokenfinger.tracker.application.ResolvedProblem
import com.brokenfinger.tracker.domain.ChallengeableId
import com.brokenfinger.tracker.domain.LessonId
import com.brokenfinger.tracker.protocol.parse.ProblemIdentityPage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a lesson's channel identifiers from its problem page, once (#114).
 *
 * **Cached by lesson and never invalidated**, because protocol §3 measured both values as
 * fixed per problem and independent of language. The sensor heartbeats every 30 seconds, so
 * a cache miss on each of those would mean one page fetch per problem per half-minute
 * against Programmers — development-rules §9.3 asks the opposite of that.
 *
 * Only successes are cached. A failure is usually an expired session or a network blip, and
 * remembering it would keep answering "no" long after the user fixed the cookie.
 */
class PageProblemIdentityResolver(private val pageBase: String = DEFAULT_BASE, private val pages: PageSource) :
    ProblemIdentityResolver {
    private val known = ConcurrentHashMap<Long, ResolvedProblem>()

    override suspend fun resolve(lessonId: LessonId, language: String): ResolvedProblem? {
        known[lessonId.value]?.let { return it }
        val resolved = fetched(lessonId, language) ?: return null
        known[lessonId.value] = resolved
        return resolved
    }

    private suspend fun fetched(lessonId: LessonId, language: String): ResolvedProblem? {
        val response = runCatching { pages.get(urlOf(lessonId, language)) }.getOrElse {
            // The exception's type only — a message could carry the URL, and the URL carries
            // which problem a learner is solving (dev rules §7).
            logger.warn("Lesson {}: problem page fetch failed ({})", lessonId.value, it.javaClass.simpleName)
            return null
        }
        if (response.status != OK) {
            logger.warn("Lesson {}: problem page answered {}", lessonId.value, response.status)
            return null
        }
        val identity = ProblemIdentityPage.identityOf(response.body) ?: run {
            logger.warn(
                "Lesson {}: the problem page carried no channel identifiers — " +
                    "an expired session redirects here, so check .ps/session first",
                lessonId.value,
            )
            return null
        }
        return ResolvedProblem(ChallengeableId(identity.challengeableId), identity.kind)
    }

    // The cookie rides on the PageSource; it is never interpolated into a URL or a message.
    private fun urlOf(lessonId: LessonId, language: String): String =
        "$pageBase/learn/courses/30/lessons/${lessonId.value}?language=$language"

    private companion object {
        val logger = LoggerFactory.getLogger(PageProblemIdentityResolver::class.java)!!
        const val DEFAULT_BASE = "https://school.programmers.co.kr"
        const val OK = 200
    }
}
