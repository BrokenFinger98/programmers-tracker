package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.SubmissionRecord
import java.nio.file.Path

/**
 * The record repository's git history — an outbound port (dev rules §1).
 *
 * Git is a **separate, retryable reconciliation step, not a step inside the write path**
 * ([[decisions/2026-08-05-write-serialization]] decision 4). Two consequences are part of
 * this contract rather than of any one implementation:
 *
 * - **Nothing here ever throws.** A method reports whether it got its work done, and a
 *   failure is logged where it happened. A capture whose verdict can never be recaptured
 *   must not be lost because a commit was blocked (protocol doc §11).
 * - **Every method is safe to call again.** Work a failed call left behind is picked up by
 *   the next [reconcile], so a `false` costs a delay rather than a record.
 */
interface GitSync {
    /**
     * One `submit`, one commit (design §4.6). A `run` is not committed on its own — it is
     * pressed dozens of times while writing code, and its edits ride along with the next
     * submit or a [reconcile].
     *
     * Staging is **path-scoped**: only [paths] enter the commit, so whatever else is dirty
     * or already staged in the record repository stays out of it.
     *
     * Returns whether the record is now committed, which includes "there was nothing to
     * commit". A push that failed afterwards does not make it false — the commit is the
     * durable part, and pushing is retried later.
     */
    fun commitSubmission(record: SubmissionRecord, paths: List<Path>): Boolean

    /**
     * Commits whatever is uncommitted, idempotently: it does nothing on a clean tree and is
     * safe to call repeatedly. This is the retry mechanism for everything the scoped path
     * above failed to commit.
     */
    fun reconcile(): Boolean

    /**
     * Pushes the current branch. Called on a pass and available as a manual trigger
     * (MCP `push()`, design §4.6).
     */
    fun push(): Boolean
}
