package com.brokenfinger.tracker

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
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
 * A fourth property is not a place: `tracker.github.token`, pinned to nothing. `application.yml`
 * reads it from `GITHUB_TOKEN`, and Spring's other sources can set it too: `TRACKER_GITHUB_TOKEN`,
 * a profile, a config file in the working directory. With a token, the boot of a repository that
 * has no `origin` creates a private repository on GitHub and pushes to it, and a repository this
 * test creates never has one.
 *
 * **A source is asserted for the token, not a value.** The pinned value is empty, and so is the
 * unpinned one on every machine that has no token exported. A check on the value would pass
 * wherever this test is written and fail only where a token is set, after the boot had already
 * sent its request. Which source answers the key is the same on every machine, and it is the one
 * thing the pin changes. Proving the pin by exporting a token would send the request it prevents.
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

    @Test
    fun `the github token is answered by this test and by nothing in the environment`() {
        sourceAnswering("tracker.github.token") shouldBe DYNAMIC_SOURCE
    }

    private fun setting(key: String): String? = context.environment.getProperty(key)

    // Boot's `configurationProperties` is a view over all the others, so it answers whatever they
    // do and says nothing about who holds the key. Left out, a failure names the real holder: the
    // yml, or `systemEnvironment` when `TRACKER_GITHUB_TOKEN` is exported.
    private fun sourceAnswering(key: String): String? = (context.environment as ConfigurableEnvironment)
        .propertySources
        .filterNot(ConfigurationPropertySources::isAttachedConfigurationPropertySource)
        .firstOrNull { it.containsProperty(key) }
        ?.name

    companion object {
        // What `DynamicValuesPropertySource` calls itself in Spring Framework 7. Printed from a
        // running context, not assumed; a rename shows as the token test failing.
        private const val DYNAMIC_SOURCE = "Dynamic Test Properties"

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
            registry.add("tracker.github.token") { "" }
        }
    }
}
