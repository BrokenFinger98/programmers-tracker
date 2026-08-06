package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.store.RecordRepositoryLock
import com.brokenfinger.tracker.adapter.store.RepositoryHeartbeat
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import java.nio.file.Path
import java.time.Duration

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
 *
 * **Two mechanisms, because one filesystem lies.** The kernel lock is primary and settles it
 * wherever locks work. A Docker Desktop bind mount honours none and reports success anyway
 * (#52), so the heartbeat runs behind it — it needs only `write` and `stat`, which is all such
 * a mount reliably offers ([[decisions/2026-08-07-heartbeat-behind-the-lock]]).
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
     * Runs **after** the lock, and only reaches its own check on a filesystem where the lock
     * did not settle it — on every other one the second instance never gets this far.
     */
    @Bean(destroyMethod = "close")
    fun repositoryHeartbeat(
        recordRepositoryLock: AutoCloseable,
        @Value("\${tracker.record-repo}") recordRepo: String,
        @Value("\${tracker.record-repo-lock:true}") locking: Boolean,
        @Value("\${tracker.heartbeat.watch:PT12S}") watch: Duration,
    ): RepositoryHeartbeat {
        val root = ConfiguredPath.of(recordRepo)
        val heartbeat = RepositoryHeartbeat(root, markerIn(root), watch)
        if (locking) heartbeat.claim()
        return heartbeat
    }

    /**
     * Beats faster than the watch window, so a starter that looks twice always sees a change
     * from a live holder. Equal periods would race.
     */
    @Bean
    fun heartbeatTicker(repositoryHeartbeat: RepositoryHeartbeat) = HeartbeatTicker(repositoryHeartbeat)

    /**
     * Beside the lock, and hidden from git for the same reason: `CommandLineGitSync.reconcile`
     * is `git add --all`, so a marker rewritten every few seconds in the working tree would
     * be committed and pushed to the user's records repository over and over.
     */
    private fun markerIn(root: Path): Path {
        val git = root.resolve(".git")
        return if (java.nio.file.Files.isDirectory(git)) git.resolve(MARKER) else root.resolve(MARKER)
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

        const val MARKER = ".programmers-tracker.alive"

        private val logger = LoggerFactory.getLogger(RecordRepositoryConfiguration::class.java)!!
    }
}

/** Keeps the marker changing while this instance lives. */
class HeartbeatTicker(private val heartbeat: RepositoryHeartbeat) {
    @Scheduled(fixedDelayString = "\${tracker.heartbeat.interval:PT4S}")
    fun tick() = heartbeat.beat()
}
