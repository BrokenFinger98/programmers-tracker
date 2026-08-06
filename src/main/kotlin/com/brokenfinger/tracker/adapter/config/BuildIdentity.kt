package com.brokenfinger.tracker.adapter.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Says which build is running, on the first lines of the log.
 *
 * `docker compose up` reuses a tagged image rather than rebuilding it, so a user who pulls
 * new code and starts the stack keeps running the old one. The only symptom is an **absent**
 * log line — the behaviour that changed simply does not happen — and an absent line is close
 * to invisible. It cost a whole verification pass on 2026-08-06 before anyone noticed (#60).
 *
 * That matters more here than in most tools because this one records data that cannot be
 * re-fetched. A stale image writes records with whatever verdict rules it was built from, and
 * nothing anywhere says so.
 *
 * A timestamp is enough to catch it: compare it with when you last pulled. The commit is
 * printed too when whoever built supplied one — a container build cannot read `.git/`, which
 * `.dockerignore` excludes because it carries every credential ever committed and then
 * removed.
 */
@Component
class BuildIdentity(private val builds: ObjectProvider<BuildProperties>) {
    /**
     * The stamp is optional on purpose. `BuildProperties` exists only when
     * `build-info.properties` was generated, which a plain `gradlew test` does not do —
     * requiring it would turn a missing build stamp into a context that refuses to start,
     * trading a diagnostic for an outage.
     */
    @EventListener(ContextRefreshedEvent::class)
    fun announce() {
        val build = builds.getIfAvailable() ?: return logger.info(UNSTAMPED)
        logger.info(
            "Running build {} — compiled {} from commit {}. If that predates your last pull, " +
                "you are on a stale image: rebuild with `docker compose build`.",
            build.version,
            build.time?.atZone(ZoneId.systemDefault())?.format(STAMP) ?: "at an unrecorded time",
            build.get("commit") ?: "unknown",
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(BuildIdentity::class.java)!!
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

        const val UNSTAMPED =
            "Running an unstamped build — no build-info.properties, so this instance cannot say " +
                "which source it came from. Expected from `gradlew bootRun`, never from a container."
    }
}
