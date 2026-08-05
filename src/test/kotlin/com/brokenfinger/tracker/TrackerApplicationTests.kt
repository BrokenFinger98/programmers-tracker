package com.brokenfinger.tracker

import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * The one Spring context test the test-environment ADR allows.
 *
 * Every path is redirected into a scratch directory. Booting runs the startup reconciliation,
 * and reconciliation is `git add --all` — pointed at the default `~/ps-records` it would
 * commit whatever a developer happened to have pending in their own records. A test must not
 * be able to do that, and the scratch directory is not a repository, so nothing here commits
 * at all.
 */
@SpringBootTest(
    properties = [
        "tracker.record-repo=\${java.io.tmpdir}/programmers-tracker-context-test/records",
        "tracker.raw-dir=\${java.io.tmpdir}/programmers-tracker-context-test/raw",
        "tracker.timers-file=\${java.io.tmpdir}/programmers-tracker-context-test/timers.json",
        "tracker.backup.state-file=\${java.io.tmpdir}/programmers-tracker-context-test/backup.json",
    ],
)
class TrackerApplicationTests {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        context.containsBean("trackerApplication").shouldBeTrue()
    }
}
