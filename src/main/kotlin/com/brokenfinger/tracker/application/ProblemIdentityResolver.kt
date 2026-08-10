package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.ChallengeableId
import com.brokenfinger.tracker.domain.LessonId
import com.brokenfinger.tracker.domain.ProblemKind

/** What a `/watch` caller no longer has to know: which channel a lesson's gradings ride on. */
data class ResolvedProblem(val challengeableId: ChallengeableId, val kind: ProblemKind)

/**
 * Answers "which channel does this lesson broadcast on" so `/watch` needs only the two
 * things the server genuinely cannot know — the lesson and the language (#114).
 *
 * An outbound port: the answer lives on the problem page, which is `protocol`'s business.
 *
 * **Every lookup may fail, and failing is the right answer.** A page that redirects to
 * sign-in means the session expired, and saying so at the moment the user tries to watch is
 * far better than accepting the request and failing silently at the socket minutes later.
 */
fun interface ProblemIdentityResolver {
    /** Null when the page could not be read or did not describe a family we have measured. */
    suspend fun resolve(lessonId: LessonId, language: String): ResolvedProblem?
}
