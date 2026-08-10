package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.TrackerApplication
import com.brokenfinger.tracker.adapter.store.RecordRepositoryLock
import com.brokenfinger.tracker.adapter.store.RecordRepositoryLockedException
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * **When** the refusal happens, which is a claim about Spring's lifecycle rather than about
 * our code — so it is verified by booting the real application rather than reasoned about.
 *
 * A lock acquired too late would be worthless twice over: the web server would already be
 * accepting `/watch` calls, and `reconcileAtStartup` — which is `git add --all` over the
 * record repository — would already have run against a repository someone else owns. A
 * singleton bean is built in `finishBeanFactoryInitialization`, before both. The two events
 * below are Spring's own announcements of those two moments, and neither may be published.
 *
 * This is the second Spring context in the test suite ([[decisions/2026-08-04-test-environment]]
 * allows one). It earns the exception because there is nothing else to observe: the property
 * under test is an ordering inside `SpringApplication.run`, and no fake reproduces it.
 */
class RecordRepositoryLockStartupTest {
    @Test
    @Timeout(TIMEOUT)
    fun `the second instance refuses before the server listens and before reconciliation`(@TempDir base: Path) {
        val records = Files.createDirectories(base.resolve("records"))
        val announced = ConcurrentLinkedQueue<Class<out ApplicationEvent>>()

        RecordRepositoryLock(records).use {
            val failure = runCatching { boot(base, records, announced) }.exceptionOrNull()

            val refused = refusalIn(failure).shouldNotBeNull()
            // Not decoration. A boot that reaches a DIFFERENT record repository also refuses
            // nothing and passes everything below; this is what makes the property under test
            // "refused on the repository this test owns" rather than "refused somewhere".
            refused.recordRoot shouldBe records
            // Published after the connectors accept connections, so its absence is the
            // strongest statement available that nothing was ever served.
            announced.shouldNotAnnounce(WebServerInitializedEvent::class.java)
            // `SpringApplication.run` publishes this immediately before `callRunners`, so its
            // absence means `reconcileAtStartup` never touched the repository.
            announced.shouldNotAnnounce(ApplicationStartedEvent::class.java)
        }
    }

    private fun boot(base: Path, records: Path, announced: ConcurrentLinkedQueue<Class<out ApplicationEvent>>) {
        SpringApplication(TrackerApplication::class.java)
            .apply { addInitializers({ context -> context.addApplicationListener(recorder(announced)) }) }
            .run(*argumentsFor(base, records))
            .close()
    }

    // Registered on the context rather than on the SpringApplication, so that context-scoped
    // events — the web server's among them — reach it too.
    private fun recorder(announced: ConcurrentLinkedQueue<Class<out ApplicationEvent>>) =
        ApplicationListener<ApplicationEvent> { event -> announced.add(event.javaClass) }

    /**
     * Command-line arguments, **not** `setDefaultProperties`. Default properties sit at the
     * bottom of Spring's precedence order, below `application.yml` — so an override written
     * that way is silently ignored and every path falls back to its shipped default, of which
     * `tracker.record-repo` is `~/ps-records`: a real user's solving history, reconciled with
     * `git add --all`. Measured the hard way on 2026-08-06; `scripts/no-home-writes.sh` now
     * fails the suite rather than leaving it to be noticed.
     *
     * Port 0 for the same family of reasons: a fixed port would turn a developer's already
     * running instance into a false pass.
     */
    private fun argumentsFor(base: Path, records: Path): Array<String> = arrayOf(
        "--server.port=0",
        "--tracker.record-repo=$records",
        "--tracker.session-file=${base.resolve("session")}",
        "--tracker.watch.token=not-a-real-token",
        "--tracker.watch.token-file=${base.resolve("watch-token")}",
    )

    // Subtypes count: the servlet container publishes ServletWebServerInitializedEvent, and
    // an exact-class assertion would pass while the server was merrily accepting connections.
    private fun Collection<Class<out ApplicationEvent>>.shouldNotAnnounce(event: Class<out ApplicationEvent>) {
        none { event.isAssignableFrom(it) }.shouldBeTrue()
    }

    // The refusal sits in the middle of the chain, not at either end: Spring wraps it in a
    // bean-creation failure, and it wraps the OverlappingFileLockException that caused it.
    private fun refusalIn(failure: Throwable?): RecordRepositoryLockedException? =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
            .filterIsInstance<RecordRepositoryLockedException>()
            .firstOrNull()

    private companion object {
        /** A full context boot on a cold CI runner; anything beyond this is a hang. */
        const val TIMEOUT = 120L
    }
}
