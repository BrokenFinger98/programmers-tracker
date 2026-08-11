package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SubmissionRecord
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The tick fires 1,440 times a day, so the question is not "what is the state" but "has anything
 * changed" — a warning repeated every minute is one nobody reads.
 */
class BackupReporterTest {
    private val clock = MovableClock()
    private val log = RememberedBackup()
    private val git = RemoteToggle()

    @Test
    fun `the first look reports, because nothing has been announced`() {
        git.present = true
        log.at = clock.instant().minus(Duration.ofDays(9))

        reporter().changed() shouldBe BackupAge.Stale(days = 9, everPushed = true)
    }

    @Test
    fun `looking again with nothing changed says nothing`() {
        git.present = true
        log.at = clock.instant().minus(Duration.ofDays(9))
        val reporter = reporter()

        reporter.changed()
        repeat(5) { reporter.changed().shouldBeNull() }
    }

    /**
     * A `Stale` that grew from nine days to ten is the same news. Comparing the number would fire
     * once a day forever, which is the noise this exists to avoid.
     */
    @Test
    fun `a stale gap growing is not new news`() {
        git.present = true
        log.at = clock.instant().minus(Duration.ofDays(9))
        val reporter = reporter()
        reporter.changed()

        clock.advance(Duration.ofDays(3))

        reporter.changed().shouldBeNull()
    }

    /**
     * The all-clear. A warning with no matching recovery leaves the reader unable to tell a fixed
     * problem from an unreported one.
     */
    @Test
    fun `recovering is announced`() {
        git.present = true
        log.at = clock.instant().minus(Duration.ofDays(9))
        val reporter = reporter()
        reporter.changed()

        log.at = clock.instant()

        reporter.changed() shouldBe BackupAge.Current
    }

    /** Never-pushed and gone-stale ask the user for different things, so they are different news. */
    @Test
    fun `never pushed and then stale are separate announcements`() {
        git.present = true
        val reporter = reporter()

        reporter.changed() shouldBe BackupAge.Stale(days = 0, everPushed = false)
        log.at = clock.instant().minus(Duration.ofDays(4))
        reporter.changed() shouldBe BackupAge.Stale(days = 4, everPushed = true)
    }

    /** `current()` is the boot path: it states the answer whatever was said before, and primes. */
    @Test
    fun `current always answers and silences the next tick`() {
        git.present = true
        log.at = clock.instant().minus(Duration.ofDays(9))
        val reporter = reporter()

        reporter.current() shouldBe BackupAge.Stale(days = 9, everPushed = true)
        reporter.changed().shouldBeNull()
    }

    private fun reporter() = BackupReporter(log, git, clock)

    private class RememberedBackup : BackupLog {
        var at: Instant? = null

        override fun lastSuccessAt(): Instant? = at

        override fun succeededAt(instant: Instant) {
            at = instant
        }
    }

    private class RemoteToggle : GitSync {
        var present = false

        override fun hasRemote(): Boolean = present

        override fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean = true

        override fun reconcile(): Boolean = true

        override fun push(): Boolean = true
    }

    private class MovableClock : Clock() {
        private var now = Instant.parse("2026-08-11T09:00:00Z")

        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
