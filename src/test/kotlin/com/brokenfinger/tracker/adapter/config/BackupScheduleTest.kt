package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.application.BackupAge
import com.brokenfinger.tracker.application.BackupReporter
import com.brokenfinger.tracker.application.DailyBackup
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * The tick that asks whether the backup is due, and says something only when the answer changed.
 *
 * It fires 1,440 times a day, so most of its job is **not** speaking: a warning repeated that
 * often is one nobody reads (#185), while an all-clear that never comes leaves the reader unable
 * to tell a fixed problem from an unreported one. The class was previously exercised only by the
 * running application, which left every branch here unasserted (#272).
 */
class BackupScheduleTest {
    private val backup = mockk<DailyBackup>(relaxed = true)
    private val reporter = mockk<BackupReporter>()

    @Test
    fun `every tick asks the backup whether it is due and the reporter whether to speak`() {
        every { reporter.changed() } returns null

        schedule().tick()

        verify(exactly = 1) { backup.runIfDue() }
        verify(exactly = 1) { reporter.changed() }
    }

    /**
     * Each arm of the announcement, driven by what the reporter can return. The assertion is that
     * every one is reachable and none throws — what they emit is a log line, and pinning its text
     * would test the string rather than the branch that chose it.
     */
    @Test
    fun `announces every kind of answer the reporter can give`() {
        val answers = listOf(
            BackupAge.Current,
            BackupAge.NoRemote,
            // Never pushed and stale-since-forever are different sentences on purpose: one says
            // the records have never left this machine, the other says they stopped leaving.
            BackupAge.Stale(days = 0, everPushed = false),
            BackupAge.Stale(days = 9, everPushed = true),
        )

        answers.forEach { age ->
            every { reporter.changed() } returns age
            schedule().tick()
        }

        verify(exactly = answers.size) { backup.runIfDue() }
    }

    private fun schedule() = BackupSchedule(backup, reporter)
}
