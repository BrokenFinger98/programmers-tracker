package com.brokenfinger.tracker.adapter.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZoneId

class ConfiguredZoneTest {
    private val here = ZoneId.of("Asia/Seoul")

    @Test
    fun `a configured zone is the zone`() {
        ConfiguredZone.of("Europe/Berlin", fallback = here) shouldBe ZoneId.of("Europe/Berlin")
    }

    /**
     * The default is **the clock the process is running on**, not a zone written into this
     * repository. `Asia/Seoul` was the shipped default until #243, which is the developer's
     * environment becoming everyone's — the thing dev rules §9.1 and this file's own neighbour
     * comment already forbade.
     */
    @Test
    fun `nothing configured falls back to the clock the process runs on`() {
        ConfiguredZone.of("", fallback = here) shouldBe here
        ConfiguredZone.of("   ", fallback = here) shouldBe here
    }

    @Test
    fun `surrounding whitespace is not part of a zone name`() {
        ConfiguredZone.of("  Asia/Seoul  ", fallback = ZoneId.of("UTC")) shouldBe here
    }

    /**
     * Strict, because a value we are given at a boundary is ours to validate (dev rules §4) and
     * a mistyped zone must not quietly become UTC. Starting is where a configuration error is
     * cheap to fix; the first record stamped nine hours out is where it is not.
     */
    @Test
    fun `a zone that does not exist refuses rather than defaulting`() {
        shouldThrow<Exception> { ConfiguredZone.of("Asia/Seuol", fallback = here) }
    }
}
