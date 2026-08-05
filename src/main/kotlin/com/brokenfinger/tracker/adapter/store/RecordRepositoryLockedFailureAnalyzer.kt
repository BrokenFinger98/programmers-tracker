package com.brokenfinger.tracker.adapter.store

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/**
 * Turns the refused repository into Spring Boot's APPLICATION FAILED TO START block —
 * description, action, and no stack trace.
 *
 * The refusal is the one failure this tool deliberately chooses over degrading, so it is
 * also the one a user is most likely to meet without expecting it. A `BeanCreationException`
 * spanning forty frames would answer "something threw"; the question is "why will this not
 * start, and what do I do about it", and these two paragraphs answer exactly that.
 *
 * Registered in `META-INF/spring.factories` — the only place Spring Boot looks.
 */
class RecordRepositoryLockedFailureAnalyzer : AbstractFailureAnalyzer<RecordRepositoryLockedException>() {
    override fun analyze(rootFailure: Throwable, cause: RecordRepositoryLockedException): FailureAnalysis =
        FailureAnalysis(descriptionOf(cause), actionFor(cause), cause)

    private fun descriptionOf(cause: RecordRepositoryLockedException): String {
        if (!cause.refusedByAnotherHolder) return unavailableOn(cause)
        return "The record repository ${cause.recordRoot} is already held by another " +
            "programmers-tracker instance, which is holding ${cause.lockFile}. Two instances " +
            "recording into one repository interleave attempt numbers and fight over the git index, " +
            "so this one refuses to start rather than corrupt the records."
    }

    private fun actionFor(cause: RecordRepositoryLockedException): String {
        if (!cause.refusedByAnotherHolder) return moveOrDisable(cause)
        return "Run exactly one instance per record repository — a container and a native run count " +
            "as two, and `docker compose up` while `bootRun` is alive is the usual way this happens. " +
            "Stop the other one and start again. Nothing needs deleting: the lock is released by the " +
            "operating system the moment that process exits, however it exits."
    }

    private fun unavailableOn(cause: RecordRepositoryLockedException): String =
        "The filesystem holding the record repository ${cause.recordRoot} could not provide a file " +
            "lock (${cause.cause?.message}). Network filesystems and some virtualised mounts do not " +
            "support one, and without it nothing can tell a second instance apart from the first."

    private fun moveOrDisable(cause: RecordRepositoryLockedException): String =
        "Put the record repository on a local filesystem, or set TRACKER_RECORD_REPO_LOCK=false to " +
            "start without the lock. Turning it off is not free: nothing then stops a second " +
            "instance, so run exactly one — ${cause.recordRoot} is written by whoever holds it."
}
