package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.domain.ProblemExample
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.domain.RatingChange
import com.brokenfinger.tracker.domain.Score
import com.brokenfinger.tracker.domain.TerminalKind
import com.brokenfinger.tracker.domain.TestcaseResult
import com.brokenfinger.tracker.protocol.ChallengeableType
import com.brokenfinger.tracker.protocol.message.SubmitMessage
import org.slf4j.LoggerFactory

/**
 * The one crossing from the wire format to the domain (dev rules §2.1).
 *
 * Everything above this object sees [GradingFrameFacts] — [TestcaseResult], [TerminalKind]
 * and [GradingAction] — and nothing above it may learn that a testcase id is spelled
 * `testcaseId` on an algorithm stream and `testcase_id` on a database one. When Programmers
 * renames a field, this file and [SubmitMessage] are the whole blast radius.
 *
 * Mapping is lenient throughout: a frame it cannot interpret yields null, never a default
 * and never an exception. The frame itself is still preserved by the caller, so a null here
 * costs interpretation, not data.
 */
object GradingMessageMapper {
    private val logger = LoggerFactory.getLogger(GradingMessageMapper::class.java)

    fun problemKindOf(type: ChallengeableType): ProblemKind = when (type) {
        ChallengeableType.ALGORITHM -> ProblemKind.ALGORITHM
        ChallengeableType.DATABASE -> ProblemKind.DATABASE
    }

    /**
     * Everything one message contributes to a grading, read in a single pass. This is the
     * form `application` consumes ([[decisions/2026-08-05-protocol-dependency-direction]]
     * decision 2); above this call nothing knows that message *types* exist at all.
     */
    fun factsOf(message: SubmitMessage) = GradingFrameFacts(
        action = actionOf(message),
        terminalKind = terminalKindOf(message),
        testcase = testcaseOf(message),
        announcedTestcaseIds = announcedTestcaseIds(message),
        announcedTestcaseCount = announcedTestcaseCount(message),
        announcedExamples = announcedExamples(message),
        errorText = errorTextOf(message),
        startsGrading = message is SubmitMessage.Start,
        outsideGrading = outsideGrading(message),
        score = scoreOf(message),
        rating = ratingOf(message),
    )

    /**
     * Only `result_lesson_challenge` reports either, and only for algorithm gradings — the SQL
     * path sends neither (protocol §6), which is why both stay lenient rather than defaulted.
     *
     * `scores`, the per-category array, is deliberately not carried: §14 lists its two-entry
     * shape for efficiency-test problems as **never triggered**, so mapping it would be guessing
     * a shape. `userScore`/`perfectScore` is the pair that has been measured.
     */
    private fun scoreOf(message: SubmitMessage): Score? = when (message) {
        is SubmitMessage.ResultLessonChallenge -> Score.ofReceived(message.userScore, message.perfectScore)
        else -> null
    }

    private fun ratingOf(message: SubmitMessage): RatingChange? = when (message) {
        is SubmitMessage.ResultLessonChallenge -> RatingChange.ofReceived(message.oldUserRating, message.newUserRating)
        else -> null
    }

    fun actionOf(message: SubmitMessage): GradingAction? = GradingAction.ofReceived(rawActionOf(message))

    fun terminalKindOf(message: SubmitMessage): TerminalKind? = when (message) {
        is SubmitMessage.Finish -> TerminalKind.FINISH
        is SubmitMessage.ResultLessonChallenge -> TerminalKind.RESULT_LESSON_CHALLENGE
        is SubmitMessage.Error -> TerminalKind.ERROR
        is SubmitMessage.Result -> TerminalKind.RESULT
        is SubmitMessage.Unknown, is SubmitMessage.Start,
        is SubmitMessage.TestGroup, is SubmitMessage.Testcase,
        -> null
    }

    /**
     * A database run reports its only result on the finish frame itself (protocol doc §6),
     * which is why finish is a testcase source and not merely a stream end.
     */
    fun testcaseOf(message: SubmitMessage): TestcaseResult? = when (message) {
        is SubmitMessage.Testcase -> gradedCase(message)
        is SubmitMessage.Finish -> gradedCase(message)
        is SubmitMessage.Start, is SubmitMessage.TestGroup, is SubmitMessage.ResultLessonChallenge,
        is SubmitMessage.Result, is SubmitMessage.Error, is SubmitMessage.Unknown,
        -> null
    }

    /**
     * Ids the stream promised to grade — `test_group.testcaseIds` on algorithm streams,
     * `start.testcase_ids` on database ones. The caller checks arrivals against this rather
     * than trusting that sorting implies completeness (design §4.2).
     */
    fun announcedTestcaseIds(message: SubmitMessage): List<Long> = when (message) {
        is SubmitMessage.Start -> message.testcaseIds.orEmpty()
        is SubmitMessage.TestGroup -> message.testcaseIds.orEmpty()
        is SubmitMessage.Testcase, is SubmitMessage.ResultLessonChallenge, is SubmitMessage.Finish,
        is SubmitMessage.Result, is SubmitMessage.Error, is SubmitMessage.Unknown,
        -> emptyList()
    }

    /**
     * How many cases the stream promised, when it promised a count instead of ids.
     *
     * A `run` announces its work as the example testcases inline on `start` (protocol doc §7,
     * `fixtures/algorithm-run-pass.jsonl`) and never sends `testcase_ids`. Without this the
     * completeness check has nothing to compare against and every run is filed as unverified
     * — a systematically misleading flag on the most common action there is.
     */
    fun announcedTestcaseCount(message: SubmitMessage): Int? = when (message) {
        is SubmitMessage.Start -> message.exampleTestcases?.size
        else -> null
    }

    /**
     * The example pairs carried inline on `run`'s `start` (protocol §7), as domain values.
     * The wire names (`ExampleTestcase`) stop here, per the dependency direction
     * ([[decisions/2026-08-05-protocol-dependency-direction]]).
     */
    fun announcedExamples(message: SubmitMessage): List<ProblemExample> {
        if (message !is SubmitMessage.Start) return emptyList()
        val pairs = message.exampleTestcases?.map { it.input to it.output } ?: return emptyList()
        return ProblemExample.ofReceived(pairs)
    }

    /** Full error text of a run-path failure, unescaped here so no caller has to (§7). */
    fun errorTextOf(message: SubmitMessage): String? {
        if (message !is SubmitMessage.Error) return null
        return message.msg?.let(HtmlText::unescape)
    }

    private fun rawActionOf(message: SubmitMessage): String? = when (message) {
        is SubmitMessage.Start -> message.action
        is SubmitMessage.TestGroup -> message.action
        is SubmitMessage.Testcase -> message.action
        is SubmitMessage.ResultLessonChallenge -> message.action
        is SubmitMessage.Finish -> message.action
        is SubmitMessage.Result -> message.action
        is SubmitMessage.Error -> message.action
        is SubmitMessage.Unknown -> null
    }

    /**
     * The actions protocol §8 catalogues that are not gradings. **Both spellings of the check
     * are needed**, and for different reasons:
     *
     * - a `reset` frame carries no `type` at all, so it parses as [SubmitMessage.Unknown] and
     *   its action is only reachable through the raw object (measured on lesson 181952,
     *   2026-08-12)
     * - a `save` frame's types include `result`, which parses as a perfectly ordinary run
     *   result — the action string is the only thing distinguishing it from one
     *
     * [rawActionOf] is deliberately left alone rather than taught to read `Unknown`: it feeds
     * [actionOf], and letting an unrecognised frame start reporting RUN or SUBMIT would change
     * assembly on a path nothing here has measured.
     */
    private fun outsideGrading(message: SubmitMessage): Boolean = rawOf(message)?.lowercase() in NON_GRADING_ACTIONS

    private fun rawOf(message: SubmitMessage): String? = when (message) {
        is SubmitMessage.Unknown -> message.action()
        else -> rawActionOf(message)
    }

    /**
     * Protocol §8's non-grading actions, lower-cased for comparison.
     *
     * **They are not equally well evidenced, and the difference is worth keeping visible.**
     * `reset` is measured — a captured broadcast, fixture `algorithm-reset.jsonl`. `save` is
     * from §8's catalogue, which was extracted from Programmers' own bundle rather than seen
     * on the wire here. It is included because the failure is asymmetric: an unmeasured entry
     * that never arrives costs nothing, while omitting one that does arrive reopens exactly
     * the defect this closes. If a `save` broadcast is ever captured, it gets a fixture.
     */
    private val NON_GRADING_ACTIONS = setOf("save", "reset")

    // A submit frame carries `testcaseId`; a run frame carries a 0-based `index` instead
    // (protocol doc §7, fixtures/algorithm-run-pass.jsonl). Both identify a case within one
    // session, and a session is either a run or a submit — never both.
    private fun gradedCase(message: SubmitMessage.Testcase): TestcaseResult? {
        val id = message.testcaseId ?: message.index?.toLong() ?: return declined("testcase")
        return TestcaseResult(id, message.passed, message.msg, message.runTime, message.memorySize)
    }

    // Timing and memory are not reported on this path at all — absent, not zero.
    private fun gradedCase(message: SubmitMessage.Finish): TestcaseResult? {
        val id = message.testcaseId ?: return null
        return TestcaseResult(id, message.passed, message.msg, null, null)
    }

    // Substituting an id would file a real result under a fabricated one, which is worse
    // than leaving it unmapped; the completeness check surfaces the gap either way.
    private fun declined(frameType: String): TestcaseResult? {
        logger.warn("Frame '{}' carried no testcase id — left unmapped rather than defaulted", frameType)
        return null
    }
}
