package com.brokenfinger.tracker

import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * The one Spring context test the test-environment ADR allows.
 *
 * One property redirects everything, because state is derived from the record repository
 * rather than configured beside it (#126). Booting runs the startup reconciliation, and
 * reconciliation is `git add --all` — pointed at the default `~/ps-records` it would commit
 * whatever a developer happened to have pending in their own records. A test must not be
 * able to do that, and the scratch directory is not a repository, so nothing here commits at
 * all.
 */
@SpringBootTest(
    properties = ["tracker.record-repo=\${java.io.tmpdir}/programmers-tracker-context-test/records"],
)
class TrackerApplicationTests {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        context.containsBean("trackerApplication").shouldBeTrue()
    }
}
