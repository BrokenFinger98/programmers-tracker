package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.SensorObservation
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.name

class FileProblemTimerTest {
    @TempDir
    lateinit var root: Path

    private val clock = MovableClock()

    // Lazy: @TempDir is injected after construction, so `root` is not readable in an initializer.
    private val timer by lazy { FileProblemTimer.under(root, clock) }

    @Test
    fun `a lesson that was never seen has elapsed zero`() {
        timer.elapsedSecOf(120804) shouldBe 0
    }

    @Test
    fun `reading does not start the timer — only startIfAbsent creates it`() {
        // Otherwise "never seen" and "just started" would be the same event, and the first
        // elapsed reading of every problem would silently be the moment we happened to ask.
        timer.elapsedSecOf(120804)

        clock.advance(Duration.ofMinutes(10))

        timer.elapsedSecOf(120804) shouldBe 0
        Files.exists(timersFile()) shouldBe false
    }

    @Test
    fun `elapsed counts from the moment the problem was first seen`() {
        timer.startIfAbsent(120804)

        clock.advance(Duration.ofMinutes(14).plusSeconds(7))

        timer.elapsedSecOf(120804) shouldBe 847
    }

    @Test
    fun `re-opening the same problem never restarts the timer — it accumulates`() {
        timer.startIfAbsent(120804)
        clock.advance(Duration.ofMinutes(20))

        timer.startIfAbsent(120804)

        timer.elapsedSecOf(120804) shouldBe Duration.ofMinutes(20).seconds
    }

    @Test
    fun `the start time survives a restart, because it lives in the file and not in memory`() {
        timer.startIfAbsent(120804)
        clock.advance(Duration.ofMinutes(5))

        FileProblemTimer.under(root, clock).elapsedSecOf(120804) shouldBe Duration.ofMinutes(5).seconds
    }

    @Test
    fun `timers of different problems do not disturb each other`() {
        timer.startIfAbsent(120804)
        clock.advance(Duration.ofMinutes(3))
        timer.startIfAbsent(131528)

        clock.advance(Duration.ofMinutes(1))

        timer.elapsedSecOf(120804) shouldBe Duration.ofMinutes(4).seconds
        timer.elapsedSecOf(131528) shouldBe Duration.ofMinutes(1).seconds
    }

    @Test
    fun `a document torn by a crash is read as empty rather than throwing`() {
        // The whole file is at most a set of start times; refusing to work because of it would
        // block capture, which is the one thing that must never be lost (protocol §11).
        writeRaw("""{"120804":175440""")

        timer.elapsedSecOf(120804) shouldBe 0
    }

    @Test
    fun `a torn document does not block a new timer from being recorded`() {
        writeRaw("""{"120804":175440""")

        timer.startIfAbsent(120804)
        clock.advance(Duration.ofMinutes(2))

        timer.elapsedSecOf(120804) shouldBe Duration.ofMinutes(2).seconds
    }

    @Test
    fun `an entry whose value is not a number is ignored, not coerced into a start time`() {
        writeRaw("""{"120804":"a-while-ago"}""")

        timer.elapsedSecOf(120804) shouldBe 0
    }

    @Test
    fun `a clock that moved backwards yields zero, never a negative elapsed`() {
        timer.startIfAbsent(120804)

        clock.advance(Duration.ofMinutes(-5))

        timer.elapsedSecOf(120804) shouldBe 0
    }

    @Test
    fun `the file is written whole, leaving no temporary debris a reader could pick up`() {
        timer.startIfAbsent(120804)
        timer.startIfAbsent(131528)

        stateDirEntries() shouldContainExactly listOf("timers.json")
    }

    @Test
    fun `the document is one plain entry per lesson, readable without this class`() {
        timer.startIfAbsent(120804)

        Files.readString(timersFile()) shouldBe """{"120804":{"startedAt":1785888000}}"""
    }

    /**
     * The shape before #120 mapped a lesson straight to its epoch second, and files in that
     * form exist on real machines — the developer's had five problems running when this
     * changed. Dropping them would reset those clocks and put a wrong `elapsedSec` on the
     * next record, which is worse than an absent one.
     */
    @Test
    fun `a document in the pre-observation shape still yields its start times`() {
        writeRaw("""{"120804":1785888000,"131528":1785887000}""")

        timer.elapsedSecOf(120804) shouldBe 0
        timer.elapsedSecOf(131528) shouldBe 1000
        timer.observationOf(120804).shouldBeNull()
    }

    /** And a start on a legacy document neither loses the old entries nor rewrites them wrongly. */
    @Test
    fun `starting a new problem beside legacy entries keeps them`() {
        writeRaw("""{"120804":1785888000}""")

        timer.startIfAbsent(131528)

        timer.elapsedSecOf(120804) shouldBe 0
        timer.elapsedSecOf(131528) shouldBe 0
    }

    // Sensor observations (#120) --------------------------------------------------------------

    @Test
    fun `an observation is kept and read back`() {
        timer.startIfAbsent(120804)

        timer.observed(120804, SensorObservation(focusedSec = 420, sawQuestions = true))

        timer.observationOf(120804) shouldBe SensorObservation(420, true)
    }

    /** Cumulative, not incremental: the newest heartbeat carries the whole answer. */
    @Test
    fun `a later observation replaces the earlier one`() {
        timer.startIfAbsent(120804)
        timer.observed(120804, SensorObservation(focusedSec = 60, sawQuestions = false))

        timer.observed(120804, SensorObservation(focusedSec = 420, sawQuestions = true))

        timer.observationOf(120804) shouldBe SensorObservation(420, true)
    }

    /**
     * The clock starts when a problem is *announced*, so a telemetry message must not start
     * one — that would put a measured-looking elapsed time on a problem nothing is watching.
     */
    @Test
    fun `an observation for a problem with no timer is ignored`() {
        timer.observed(120804, SensorObservation(focusedSec = 420, sawQuestions = true))

        timer.observationOf(120804).shouldBeNull()
        timer.elapsedSecOf(120804) shouldBe 0
    }

    /** Absent rather than zero: a reader must tell "not seen" from "seen and it was nothing". */
    @Test
    fun `a problem with no observation writes no observation fields`() {
        timer.startIfAbsent(120804)

        Files.readString(timersFile()) shouldContain "startedAt"
        Files.readString(timersFile()) shouldNotContain "focusedSec"
    }

    private fun writeRaw(text: String) {
        Files.createDirectories(timersFile().parent)
        Files.writeString(timersFile(), text)
    }

    private fun timersFile(): Path = root.resolve(".ps/timers.json")

    private fun stateDirEntries(): List<String> =
        Files.list(timersFile().parent).use { entries -> entries.map { it.name }.sorted().toList() }

    /** A clock the test moves by hand — elapsed time is the whole subject here. */
    private class MovableClock : Clock() {
        private var current = Instant.parse("2026-08-05T00:00:00Z")

        fun advance(amount: Duration) {
            current = current.plus(amount)
        }

        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }
}
