package com.brokenfinger.tracker.adapter.git

/**
 * Whether a push goes where `ls-remote` asks what is held (#405), so that what each destination says it holds is
 * what the push leaves out. A push URL is git's answer, a `url.<base>.pushInsteadOf` or `insteadOf` rule applied
 * once; handed to `ls-remote`, an `insteadOf` rule rewrote it a second time — the gate critic's B to A, then A to
 * C — and C said what it held while the push went to A, taking a token only C held. A branch whose remote is a URL
 * goes to `ls-remote` and to the push as it is, each rewriting it once, alike unless a `pushInsteadOf` rule
 * rewrites the push's alone. Git that cannot say is no agreement.
 */
internal class PushRewrites(private val git: GitCalls) {
    /** Whether [destinations], where a push to [remote] goes, are where `ls-remote` asks. */
    fun agree(remote: String, destinations: List<String>): Boolean {
        if (destinations == listOf(remote)) return !pushRewritten(remote)
        return destinations.all { askedAs(it) == it }
    }

    // What `ls-remote` would ask for [url], its `insteadOf` rules applied, asking nothing (`--get-url`).
    private fun askedAs(url: String): String? =
        git.answer(listOf("ls-remote", "--get-url", url)).takeIf { it.succeeded() }?.stdout?.trim()

    // Whether a `pushInsteadOf` rule's prefix starts [url]: git rewrites by the longest one that does, so any one is
    // a rewrite. Rules git could not list — an error, or a listing cut off by its timeout — may hold one.
    private fun pushRewritten(url: String): Boolean {
        val rules = git.answer(listOf("config", "--null", "--get-regexp", PUSH_INSTEAD_OF))
        if (!rules.succeeded() && rules.code != NO_KEY_MATCHED) return true
        return prefixesIn(rules.stdout).any { url.startsWith(it) }
    }

    // Each rule's value: with `--null`, a key, a newline and a value, then a NUL. A key never holds a newline, and a
    // base can hold a space, which is what parts them without it (measured).
    private fun prefixesIn(listing: String): List<String> =
        listing.split(NUL).map { it.substringAfter('\n', "") }.filter { it.isNotEmpty() }

    private companion object {
        /** Every `url.<base>.pushInsteadOf` rule, as `git config --get-regexp` matches its lowercased key. */
        const val PUSH_INSTEAD_OF = "^url\\..*\\.pushinsteadof$"

        /** `git config --get-regexp` exits 1 when no key matches, which is no error. */
        const val NO_KEY_MATCHED = 1

        const val NUL = '\u0000'
    }
}
