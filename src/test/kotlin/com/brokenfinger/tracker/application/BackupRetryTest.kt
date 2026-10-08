package com.brokenfinger.tracker.application

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant

/**
 * Unit test for the backoff a scheduled backup that did not count is tried again on (#390): no clock,
 * no git, only instants in and the answer to "does this check wait?" out (dev rules §3). The waits are
 * read off that answer alone, a minute at a time, never off the retry's state.
 */
class BackupRetryTest {
    @Test
    fun `the first failure is tried again a minute later`() {
        val retry = BackupRetry.of(null, DUE, FAILED)

        retry.waits(DUE, FAILED) shouldBe true
        retry.waits(DUE, FAILED.plusSeconds(59)) shouldBe true
        retry.waits(DUE, FAILED.plusSeconds(60)) shouldBe false
    }

    /**
     * A clock set back after a failure does not stretch the wait (the review of #399): set back 10, 30 or
     * 60 minutes, the retry came that much later. A check before the failure cannot tell how long it has
     * been since, so it does not wait.
     */
    @ParameterizedTest
    @ValueSource(longs = [10, 30, 60])
    fun `a clock set back after a failure does not wait`(minutes: Long) {
        val (atTheCap, failedAt) = retriesOf(failures = 7).last()

        atTheCap.waits(DUE, failedAt.minus(Duration.ofMinutes(minutes))) shouldBe false
    }

    @Test
    fun `each further failure doubles the wait, up to an hour`() {
        waitsOf(failures = 9) shouldContainExactly listOf(1L, 2, 4, 8, 16, 32, 60, 60, 60).map(Duration::ofMinutes)
    }

    /**
     * The doubling stops at the hour, every time after the seventh failure. A minute doubled once per
     * failure no longer fits a `Duration` from the 59th on, so the shift itself has to stop too.
     */
    @Test
    fun `a long run of failures waits an hour every time`() {
        waitsOf(failures = 200).drop(6).distinct() shouldContainExactly listOf(Duration.ofHours(1))
    }

    /** The next scheduled backup is a new one: it is tried when it falls due, whatever the last one waited for. */
    @Test
    fun `another scheduled backup does not wait`() {
        val (atTheCap, failedAt) = retriesOf(failures = 7).last()

        atTheCap.waits(NEXT_DUE, failedAt) shouldBe false // inside the hour the last one waits for
    }

    @Test
    fun `another scheduled backup counts its failures from one`() {
        val atTheCap = retriesOf(failures = 7).last().first

        val next = BackupRetry.of(atTheCap, NEXT_DUE, FAILED)

        waitOf(next, FAILED, NEXT_DUE) shouldBe Duration.ofMinutes(1)
    }

    /** Each retry after a failure at the time the one before it allowed, with that time. */
    private fun retriesOf(failures: Int): List<Pair<BackupRetry, Instant>> =
        generateSequence(BackupRetry.of(null, DUE, FAILED) to FAILED) { (retry, at) ->
            val next = at.plus(waitOf(retry, at))
            BackupRetry.of(retry, DUE, next) to next
        }.take(failures).toList()

    private fun waitsOf(failures: Int): List<Duration> = retriesOf(failures).map { (retry, at) -> waitOf(retry, at) }

    /** The first whole minute after [failedAt] at which a check for [due] no longer waits. */
    private fun waitOf(retry: BackupRetry, failedAt: Instant, due: Instant = DUE): Duration =
        (0L..MINUTES_PROBED).map(Duration::ofMinutes).first { !retry.waits(due, failedAt.plus(it)) }

    private companion object {
        /** 2026-08-05, 23:00 in Seoul: the scheduled backup that failed. */
        val DUE: Instant = Instant.parse("2026-08-05T14:00:00Z")

        /** The next day's. */
        val NEXT_DUE: Instant = Instant.parse("2026-08-06T14:00:00Z")

        /** When the first try for [DUE] failed. */
        val FAILED: Instant = Instant.parse("2026-08-05T14:00:30Z")

        /** Two hours, past any wait the backoff can ask for. */
        const val MINUTES_PROBED = 120L
    }
}
