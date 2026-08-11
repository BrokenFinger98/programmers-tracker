package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.GitSync
import com.brokenfinger.tracker.domain.SubmissionRecord
import java.nio.file.Path

// Object mother (dev rules §6.4) for tests whose subject is not git. Anything that actually
// asserts on history drives a real repository instead — see `support/git/GitWorkspace`.

/** A [GitSync] that records nothing and claims the work is done. */
fun aQuietGitSync(): GitSync = QuietGitSync

private object QuietGitSync : GitSync {
    override fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean = true

    override fun reconcile(): Boolean = true

    override fun push(): Boolean = true

    override fun hasRemote(): Boolean = true
}
