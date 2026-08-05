package com.brokenfinger.tracker.adapter.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Written after CI failed on Windows and nowhere else (#41). Every case here is about a
 * tilde that is **not** a home-directory marker, which is a shape that simply does not occur
 * on a developer's macOS or Linux machine.
 */
class ConfiguredPathTest {
    private val home: String = System.getProperty("user.home")

    @Test
    fun `a leading tilde becomes the home directory`() {
        ConfiguredPath.of("~/ps-records") shouldBe Path.of("$home/ps-records")
    }

    @Test
    fun `a bare tilde is the home directory itself`() {
        ConfiguredPath.of("~") shouldBe Path.of(home)
    }

    /**
     * The measured failure. A Windows temp directory is an 8.3 short path —
     * `C:\Users\RUNNER~1\AppData\Local\Temp` — so expanding "the first tilde" spliced the
     * home directory into the middle and produced a path Windows rejects outright.
     */
    @Test
    fun `a tilde inside a Windows short path is left alone`() {
        val shortPath = """C:\Users\RUNNER~1\AppData\Local\Temp\records"""

        val resolved = ConfiguredPath.of(shortPath).toString()

        resolved shouldBe shortPath
        resolved shouldNotContain home
    }

    @Test
    fun `an ordinary absolute path is untouched`() {
        ConfiguredPath.of("/var/tmp/records") shouldBe Path.of("/var/tmp/records")
    }

    @Test
    fun `a relative path is untouched`() {
        ConfiguredPath.of(".ps/records") shouldBe Path.of(".ps/records")
    }
}
