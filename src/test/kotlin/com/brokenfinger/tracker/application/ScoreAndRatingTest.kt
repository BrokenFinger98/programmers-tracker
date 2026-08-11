package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.support.fixtures.aSqlChannel
import com.brokenfinger.tracker.support.fixtures.anAlgorithmChannel
import com.brokenfinger.tracker.support.fixtures.anAssembledSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What the judge scored, and the rating it moved, reaching a session (#193).
 *
 * Driven from measured captures rather than hand-written frames (dev rules §6.2): the values
 * were parsed from the beginning and dropped at the `protocol → application` boundary, so a test
 * built from a synthetic frame would have proved the mapper and missed the missing wire.
 */
class ScoreAndRatingTest {
    @Test
    fun `an algorithm pass carries the score and the rating it moved`() {
        val session = settle("algorithm-pass.jsonl")

        session.score?.user shouldBe "100.0"
        session.score?.perfect shouldBe "100.0"
        session.rating?.old shouldBe 1000
        session.rating?.new shouldBe 1001
        session.rating?.changed shouldBe true
    }

    /**
     * Written from `SubmissionRecord.score`'s KDoc — *"Null for every database grading, the SQL
     * path reports no score"* — and it failed. The measured `sql-pass.jsonl` carries
     * `userScore`/`perfectScore`, and so does protocol §6's own example.
     *
     * The KDoc had been wrong since it was written and nothing caught it, because the field was
     * null for **every** grading: a wrong explanation of a right observation. What SQL genuinely
     * never sends is the per-category `scores` array and the rating.
     */
    @Test
    fun `a SQL pass carries a score but never a rating`() {
        val session = settle("sql-pass.jsonl", sql = true)

        session.score?.user shouldBe "100.0"
        session.rating.shouldBeNull()
    }

    /** A rating that did not move is still a rating — `changed` is what says so. */
    @Test
    fun `a wrong submit still reports whatever the judge scored`() {
        val session = settle("algorithm-wrong.jsonl")

        session.score shouldBe session.score
        session.rating?.let { it.changed shouldBe (it.old != it.new) }
    }

    private fun settle(fixture: String, sql: Boolean = false): GradingSession =
        anAssembledSession(fixture, channel = if (sql) aSqlChannel() else anAlgorithmChannel())
}
