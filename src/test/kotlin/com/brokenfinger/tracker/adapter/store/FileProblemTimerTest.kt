package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
    fun `the document is a plain lesson-id to epoch-second map, readable without this class`() {
        timer.startIfAbsent(120804)

        Files.readString(timersFile()) shouldBe """{"120804":1785888000}"""
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
