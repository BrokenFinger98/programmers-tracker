package com.brokenfinger.tracker

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path

/**
 * The one Spring context test the test-environment ADR allows.
 *
 * Three properties redirect everything. State is derived from the record repository rather
 * than configured beside it (#126), so that one property moves the raw frames, the timers and
 * the backup marker. The session file and the watch token are credentials and live outside it
 * on purpose, so each needs its own. Booting runs the startup reconciliation, and
 * reconciliation is `git add --all` — pointed at the default `~/ps-records` it would commit
 * whatever a developer happened to have pending in their own records. A test must not be able
 * to do that. What it does commit is a repository the boot creates for itself inside the
 * scratch directory (#258), which is deleted when the class ends.
 *
 * **One directory per run, not one per machine (#394).** The record repository used to be a
 * fixed path under `java.io.tmpdir`, which every checkout on the machine shares. The lock the
 * application takes on its record repository then refused the test itself: a second run, from
 * another worktree or an IDE beside Gradle, failed to start with `RecordRepositoryLockedException`,
 * and every run began from whatever an earlier one had left there.
 *
 * **The context is closed before its directory is deleted.** JUnit removes a static `@TempDir`
 * after the class's callbacks have run, and a cached context would still hold the lock and the
 * heartbeat marker inside it (measured with `@TempDir` alone). POSIX lets the deletion go ahead
 * under an open file; Windows, which is in the CI matrix, may not. `@DirtiesContext` closes the
 * context in `SpringExtension`'s own `afterAll`, ahead of that cleanup, so no platform is asked.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TrackerApplicationTests {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        context.containsBean("trackerApplication").shouldBeTrue()
    }

    // The keys are spelled out again, apart from the registration below, on purpose: a mistyped
    // key there falls back to the shipped default (`~/ps-records` for the first), loads just as
    // well, and is only caught by a second spelling that disagrees with it.
    @Test
    fun `the context writes only inside the directory this run owns`() {
        setting("tracker.record-repo") shouldBe scratch.resolve("records").toString()
        setting("tracker.session-file") shouldBe scratch.resolve("session").toString()
        setting("tracker.watch.token-file") shouldBe scratch.resolve("watch-token").toString()
        scratch.resolve("records").shouldExist()
    }

    private fun setting(key: String): String? = context.environment.getProperty(key)

    companion object {
        // Static, because an instance field is filled in by `beforeEach`, after Spring has built
        // the context for the instance it was just handed. A static one is filled in by
        // `beforeAll`, ahead of both.
        @TempDir
        @JvmStatic
        lateinit var scratch: Path

        @JvmStatic
        @DynamicPropertySource
        fun ownDirectory(registry: DynamicPropertyRegistry) {
            registry.add("tracker.record-repo") { scratch.resolve("records").toString() }
            registry.add("tracker.session-file") { scratch.resolve("session").toString() }
            registry.add("tracker.watch.token-file") { scratch.resolve("watch-token").toString() }
        }
    }
}
