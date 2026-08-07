package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Outcome
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Why an UNKNOWN is unknown, when the error text happens to be one we have measured.
 *
 * The need is a user standing in front of two truths at once: the browser renders a cached
 * scoreboard reading 100.0 while the record says UNKNOWN, and the only explanation sits in a
 * JSONL field nobody opens (#74). Naming the measured cause where humans look — the commit
 * subject, the README, MCP — is presentation; the classification itself lives here, once,
 * so three consumers cannot drift ([[decisions/2026-08-05-failure-taxonomy]] posture).
 *
 * The classifier is deliberately narrow. It matches **measured strings only**, and an error
 * text it does not recognise stays unexplained — never guessed at — because a wrong reason
 * printed confidently is worse than no reason (dev rules §2.3, same posture as `Unknown`
 * message preservation).
 */
class UnknownReasonTest {
    @Test
    fun `the measured cached-result message classifies as CACHED_RESULT`() {
        val reason = UnknownReason.of(Outcome.UNKNOWN, "같은 코드로 채점한 결과가 있습니다.")

        reason shouldBe UnknownReason.CACHED_RESULT
    }

    /** Measured on 181951 and 181952, 2026-08-06/07 — the string arrives with a trailing period. */
    @Test
    fun `matching is exact, not fuzzy — a reworded message is a different protocol`() {
        UnknownReason.of(Outcome.UNKNOWN, "같은 코드로 채점한 결과가 있습니다").shouldBeNull()
        UnknownReason.of(Outcome.UNKNOWN, "같은 코드로 채점한 결과가 있습니다!!").shouldBeNull()
    }

    @Test
    fun `an unmeasured error text stays unexplained rather than guessed`() {
        UnknownReason.of(Outcome.UNKNOWN, "서버 점검 중입니다.").shouldBeNull()
    }

    @Test
    fun `no error text, no reason`() {
        UnknownReason.of(Outcome.UNKNOWN, null).shouldBeNull()
    }

    /**
     * The reason is a refinement of UNKNOWN and of nothing else. A judged record with the
     * same text — a run whose error was promoted to a later submit, say — must not grow a
     * reason that contradicts its verdict.
     */
    @Test
    fun `only an UNKNOWN outcome can carry a reason`() {
        UnknownReason.of(Outcome.JUDGED, "같은 코드로 채점한 결과가 있습니다.").shouldBeNull()
        UnknownReason.of(Outcome.INCOMPLETE, "같은 코드로 채점한 결과가 있습니다.").shouldBeNull()
    }

    /** What the three consumers print. One spelling, owned here. */
    @Test
    fun `the human-readable label is short enough for a commit subject`() {
        UnknownReason.CACHED_RESULT.label shouldBe "cached result"
    }
}
