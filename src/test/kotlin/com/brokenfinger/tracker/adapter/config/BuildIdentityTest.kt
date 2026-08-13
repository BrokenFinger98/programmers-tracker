package com.brokenfinger.tracker.adapter.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import java.util.Properties

/**
 * The first lines of the log, saying which build is running.
 *
 * Every branch here is an **absent** value, which is the whole point of the class: `docker compose
 * up` reuses a tagged image, so a user who pulls new code keeps running the old one and the only
 * symptom is a line that does not appear (#60). A class whose job is to speak when data is missing
 * must not itself fall over when data is missing, and until #272 none of those paths was asserted.
 */
class BuildIdentityTest {
    @Test
    fun `an unstamped build says so instead of failing`() {
        // `gradlew test` generates no build-info.properties, so this is the ordinary local case —
        // requiring the stamp would trade a diagnostic for a context that refuses to start.
        BuildIdentity(providerOf(null)).announce()
    }

    @Test
    fun `a fully stamped build prints version, time and commit`() {
        val properties = Properties().apply {
            setProperty("version", "0.0.1-SNAPSHOT")
            setProperty("time", "2026-08-13T07:25:45Z")
            setProperty("commit", "c96b806")
        }

        BuildIdentity(providerOf(BuildProperties(properties))).announce()
    }

    /**
     * A container build cannot read `.git/` — `.dockerignore` excludes it because it carries every
     * credential ever committed and then removed — so the commit arrives as a build argument or
     * not at all. Missing time is the same shape: the jar was built without the stamp.
     */
    @Test
    fun `a build with neither commit nor time still announces itself`() {
        val properties = Properties().apply { setProperty("version", "0.0.1-SNAPSHOT") }

        BuildIdentity(providerOf(BuildProperties(properties))).announce()
    }

    private fun providerOf(build: BuildProperties?): ObjectProvider<BuildProperties> =
        mockk<ObjectProvider<BuildProperties>>().also { every { it.getIfAvailable() } returns build }
}
