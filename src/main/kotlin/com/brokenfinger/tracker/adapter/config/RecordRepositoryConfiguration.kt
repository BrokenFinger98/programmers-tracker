package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.store.RecordRepositoryLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Claims the record repository before anything can write to it
 * ([[decisions/2026-08-05-write-serialization]] decision 5).
 *
 * **A singleton bean is the whole scheduling mechanism.** Spring builds it in
 * `finishBeanFactoryInitialization`, which precedes both the web server accepting a
 * connection and every `ApplicationRunner` — so a refusal lands before `/watch` answers
 * anybody and before `reconcileAtStartup` runs `git add --all` over a repository someone
 * else owns. `RecordRepositoryLockStartupTest` boots the real application and asserts that
 * ordering rather than trusting this paragraph.
 */
@Configuration
class RecordRepositoryConfiguration {
    /**
     * @throws com.brokenfinger.tracker.adapter.store.RecordRepositoryLockedException when
     *   another instance already holds the repository — reported by
     *   `RecordRepositoryLockedFailureAnalyzer` rather than as a stack trace.
     */
    @Bean(destroyMethod = "close")
    fun recordRepositoryLock(
        @Value("\${tracker.record-repo}") recordRepo: String,
        @Value("\${tracker.record-repo-lock:true}") locking: Boolean,
    ): AutoCloseable {
        if (!locking) return unlocked(recordRepo)
        return RecordRepositoryLock(ConfiguredPath.of(recordRepo))
    }

    /**
     * The escape hatch for a filesystem that cannot lock at all. Loud on every start, because
     * an unenforced safety property that nobody remembers switching off is worse than one
     * that was never claimed.
     */
    private fun unlocked(recordRepo: String): AutoCloseable {
        logger.warn(
            "TRACKER_RECORD_REPO_LOCK is off: nothing stops a second instance writing into {}. Run exactly one.",
            recordRepo,
        )
        return AutoCloseable {}
    }

    companion object {
        /** The bean name others depend on, named once so a rename cannot break the ordering. */
        const val LOCK_BEAN = "recordRepositoryLock"

        private val logger = LoggerFactory.getLogger(RecordRepositoryConfiguration::class.java)!!
    }
}
