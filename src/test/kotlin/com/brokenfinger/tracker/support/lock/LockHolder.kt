package com.brokenfinger.tracker.support.lock

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A **second JVM** that takes the record-repository lock and holds it until told to stop.
 *
 * The failure the lock prevents is two processes, and two threads inside one JVM cannot
 * stand in for that: the JVM refuses an overlapping lock on its own, so an in-process test
 * passes whether or not the kernel is involved. This starts a real `java` off the test
 * classpath and talks to it over stdout/stdin, which is the only way to observe the property
 * that actually matters — and the only way to test [killHard], where the operating system
 * releases the lock because nobody is left to release it.
 */
class LockHolder private constructor(private val process: Process) : AutoCloseable {
    private val output = process.inputStream.bufferedReader()

    /**
     * What the child made of its attempt — [ACQUIRED] or [REFUSED]. Null if it said nothing.
     *
     * Reads past whatever the child logged: the lock announces itself at INFO, and logback
     * writes that to the same stdout, so the first line is not the answer.
     */
    fun outcome(): String? = output.lineSequence()
        .firstOrNull { it.startsWith(ACQUIRED) || it.startsWith(REFUSED) }
        ?.substringBefore(' ')

    /** Kills the child without giving it a chance to release anything (SIGKILL). */
    fun killHard() {
        process.destroyForcibly().waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Asks for a clean exit: closing stdin ends the child's read and releases the lock. */
    override fun close() {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
    }

    companion object {
        const val ACQUIRED = "ACQUIRED"
        const val REFUSED = "REFUSED"

        /** Long enough for a JVM to start on a loaded CI runner, short enough to never hang a build. */
        private const val TIMEOUT_SECONDS = 30L

        fun start(recordRoot: Path): LockHolder = LockHolder(
            ProcessBuilder(javaBinary(), "-cp", System.getProperty("java.class.path"), MAIN, recordRoot.toString())
                .redirectErrorStream(false)
                .start(),
        )

        // The running JVM's own launcher, which carries the `.exe` on Windows — CI runs the
        // three-OS matrix and `java.home/bin/java` is not an executable name there.
        private fun javaBinary(): String = ProcessHandle.current().info().command().orElseGet {
            Path.of(System.getProperty("java.home"), "bin", "java").toString()
        }

        private const val MAIN = "com.brokenfinger.tracker.support.lock.LockHolderMainKt"
    }
}
