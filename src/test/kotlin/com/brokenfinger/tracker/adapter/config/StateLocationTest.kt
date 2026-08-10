package com.brokenfinger.tracker.adapter.config

import io.kotest.matchers.paths.shouldExist
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Where the tracker's working state physically lands, tested without a Spring context
 * ([[decisions/2026-08-04-test-environment]]).
 *
 * Design §5.1 puts state in the record repository, beside the records it describes, and
 * every `under(recordRoot)` factory was written for that. Production called none of them —
 * it built these three from CWD-relative properties instead, so the raw-frame queue lived
 * wherever the process happened to start. Under Docker that is a mount and nothing shows;
 * natively, starting the server from another directory presents an empty queue and loses a
 * grading that can never be replayed (protocol §11).
 *
 * The assertion is on a real write rather than on a configured string, because the bug was
 * not a wrong value — the values were right for what they said. It was that the wiring never
 * asked the record repository where anything went.
 */
class StateLocationTest {
    @TempDir
    lateinit var scratch: Path

    private val clock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)

    private fun recordRepo(): Path = scratch.resolve("ps-records")

    @Test
    fun `raw frames are written under the record repository`() {
        val log = CaptureConfiguration().rawSessionLog(recordRepo().toString(), clock)

        log.append(log.start(181947), """{"identifier":"x"}""")

        recordRepo().resolve(".ps/raw").shouldExist()
    }

    @Test
    fun `problem timers are written under the record repository`() {
        val timer = CaptureConfiguration().problemTimer(recordRepo().toString(), clock)

        timer.startIfAbsent(181947)

        recordRepo().resolve(".ps/timers.json").shouldExist()
    }

    @Test
    fun `the backup marker is written under the record repository`() {
        val log = GitConfiguration().backupLog(recordRepo().toString())

        log.succeededAt(clock.instant())

        recordRepo().resolve(".ps/backup.json").shouldExist()
    }

    /**
     * The configured value is a path a human typed, and `tracker.record-repo` ships defaulted
     * to `~/ps-records`. These three used `Path.of` while every other consumer of the same
     * property used [ConfiguredPath], so a tilde would have produced a directory literally
     * named `~` next to the tool — and the records would have gone somewhere else entirely.
     */
    @Test
    fun `a leading tilde in the record repository is expanded`() {
        val home = System.getProperty("user.home")
        System.setProperty("user.home", scratch.toString())
        try {
            CaptureConfiguration().problemTimer("~/ps-records", clock).startIfAbsent(181947)

            recordRepo().resolve(".ps/timers.json").shouldExist()
        } finally {
            System.setProperty("user.home", home)
        }
    }
}
