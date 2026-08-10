package com.brokenfinger.tracker.protocol.parse

import com.brokenfinger.tracker.domain.ProblemKind

/**
 * Reads a problem's channel identifiers out of its page.
 *
 * These are properties of the **problem**, not of the reader: protocol §3 measured
 * `challengeable_id` and `challengeable_type` as language-independent and fixed per lesson
 * — 120803 is 14642 whatever language tab is open, and whoever opens it. So the server can
 * resolve them itself instead of making every caller of `/watch` paste them out of DevTools
 * (#114).
 *
 * Deliberately narrow: it reads two attributes off one element and ignores the rest of the
 * page, including the `data-user-id` sitting beside them. Measured markup in
 * `fixtures/lesson-page-identifiers*.html`.
 */
object ProblemIdentityPage {
    private val challengeableId = Regex("""\bdata-challengeable-id="(\d+)"""")
    private val challengeableType = Regex("""\bdata-challengeable-type="(\w+)"""")

    /**
     * Null when the page carries no identifiers at all — a sign-in redirect body is the
     * common case, and guessing an id there would subscribe to somebody else's channel.
     */
    fun identityOf(html: String): ProblemIdentity? {
        val id = challengeableId.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val kind = kindOf(challengeableType.find(html)?.groupValues?.get(1)) ?: return null
        return ProblemIdentity(id, kind)
    }

    /**
     * An unrecognised family is null, not a default. Programmers serves nine channels
     * (protocol §3) and we have measured two; picking `ALGORITHM` for a third would build a
     * subscription that is confirmed and then never delivers what we waited for.
     */
    private fun kindOf(raw: String?): ProblemKind? = when (raw) {
        "algorithm" -> ProblemKind.ALGORITHM
        "database" -> ProblemKind.DATABASE
        else -> null
    }
}

/** What a problem page says about which channel its gradings are broadcast on. */
data class ProblemIdentity(val challengeableId: Long, val kind: ProblemKind)
