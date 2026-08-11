package com.brokenfinger.tracker.application

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class BackupAgeTest {
    private val now = Instant.parse("2026-08-11T09:00:00Z")

    @Test
    fun `a recent push needs nothing said`() {
        age(pushedAgo = Duration.ofHours(3)) shouldBe BackupAge.Current
    }

    /**
     * A weekend of not opening the machine must not raise it, or the one warning that means
     * "your records are on one disk" becomes the one people scroll past.
     */
    @Test
    fun `just under the tolerance is still current`() {
        age(pushedAgo = BackupAge.TOLERANCE.minusMinutes(1)) shouldBe BackupAge.Current
    }

    @Test
    fun `past the tolerance it is stale, and says how long`() {
        age(pushedAgo = Duration.ofDays(9)) shouldBe BackupAge.Stale(days = 9, everPushed = true)
    }

    /**
     * The case that must not be an alarm. Pushing needs credentials the tool cannot invent, and
     * running without them is supported — the records still exist, they just stay here.
     */
    @Test
    fun `no remote is not a fault, however long it has been`() {
        BackupAge.of(lastSuccessAt = null, hasRemote = false, now = now) shouldBe BackupAge.NoRemote
        BackupAge.of(now.minus(Duration.ofDays(400)), hasRemote = false, now = now) shouldBe BackupAge.NoRemote
    }

    /**
     * A remote that has never been pushed to is the shape of a deploy key that never worked —
     * distinguishable from a push that used to work, because it asks the user for something
     * different.
     */
    @Test
    fun `a remote that was never pushed to is stale and says so`() {
        BackupAge.of(lastSuccessAt = null, hasRemote = true, now = now) shouldBe
            BackupAge.Stale(days = 0, everPushed = false)
    }

    private fun age(pushedAgo: Duration) =
        BackupAge.of(lastSuccessAt = now.minus(pushedAgo), hasRemote = true, now = now)
}
