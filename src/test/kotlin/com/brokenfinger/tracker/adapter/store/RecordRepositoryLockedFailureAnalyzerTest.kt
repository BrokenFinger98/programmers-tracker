package com.brokenfinger.tracker.adapter.store

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.core.io.support.SpringFactoriesLoader
import java.nio.file.Path

/**
 * What a user sees when the second instance refuses to start.
 *
 * A stack trace would answer "something threw"; the question they have is "why will this not
 * start and what do I do", and Spring Boot's failure-analysis block is the shape that answers
 * it. The registration test is not ceremony: an analyzer missing from `spring.factories` is
 * silently ignored, and the only symptom is the ugly output it exists to replace.
 */
class RecordRepositoryLockedFailureAnalyzerTest {
    private val records: Path = Path.of("/tmp/ps-records")
    private val analyzer = RecordRepositoryLockedFailureAnalyzer()

    @Test
    fun `explains the refusal in terms of the record repository`() {
        val analysis = analyzer.analyze(bootFailureOf(RecordRepositoryLockedException(records, lockFile())))

        analysis.shouldNotBeNull()
        analysis.description shouldContain records.toString()
        analysis.description shouldContain "already"
        analysis.action shouldContain "one"
    }

    @Test
    fun `says which repository a filesystem could not lock`() {
        val unsupported = RecordRepositoryLockedException(records, lockFile(), java.io.IOException("no locks here"))

        val analysis = analyzer.analyze(bootFailureOf(unsupported))

        analysis.shouldNotBeNull()
        analysis.description shouldContain records.toString()
        // The escape hatch has to be in the message: a filesystem that cannot lock would
        // otherwise leave the tool unstartable with nothing to read but a refusal.
        analysis.action shouldContain "TRACKER_RECORD_REPO_LOCK"
    }

    /**
     * Boot's own analyzers are skipped rather than instantiated: several take a `BeanFactory`
     * that only a running context can supply, and the question here is whether ours is
     * listed, not whether Spring's can be built outside Spring.
     */
    @Test
    fun `is registered where Spring Boot looks for it`() {
        val registered = SpringFactoriesLoader
            .forDefaultResourceLocation()
            .load(FailureAnalyzer::class.java, null, SpringFactoriesLoader.FailureHandler { _, _, _ -> })

        registered.map { it.javaClass } shouldContain RecordRepositoryLockedFailureAnalyzer::class.java
    }

    private fun lockFile(): Path = records.resolve(".git").resolve(RecordRepositoryLock.LOCK_FILE)

    // The shape Spring hands an analyzer: the failure is wrapped, and the analyzer has to
    // find its own cause in the chain rather than matching the top of it.
    private fun bootFailureOf(cause: Throwable): Throwable =
        BeanCreationException("Error creating bean with name 'recordRepositoryLock'", cause)
}
