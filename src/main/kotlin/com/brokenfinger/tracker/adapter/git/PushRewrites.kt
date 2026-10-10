package com.brokenfinger.tracker.adapter.git

/**
 * Whether a push goes where `ls-remote` asks what is held (#405), so that what each destination says it holds is
 * what the push leaves out. A push URL is git's answer, a `url.<base>.pushInsteadOf` or `insteadOf` rule applied
 * once; handed to `ls-remote`, an `insteadOf` rule rewrote it a second time — the gate critic's B to A, then A to
 * C — and C said what it held while the push went to A, taking a token only C held. A push URL that is also a
 * remote's name is read by `ls-remote` as that remote, by its own URL (#410). A branch whose remote is a URL goes
 * to `ls-remote` and to the push as it is, each rewriting it once, alike unless a `pushInsteadOf` rule rewrites the
 * push's alone, or the URL names a remote after all. Git that cannot say is no agreement.
 */
internal class PushRewrites(private val git: GitCalls) {
    /** Whether [destinations], where a push goes, are where `ls-remote` asks what each holds. */
    fun agree(destinations: PushDestinations): Boolean = when (destinations) {
        is PushDestinations.OfRemote -> destinations.urls.all { askedAs(it) == it }
        is PushDestinations.OfUrl -> !pushRewritten(destinations.url) && !namesARemote(destinations.url)
    }

    // What `ls-remote` would ask for [url], asking nothing (`--get-url`): its `insteadOf` rules applied, or the URL
    // of the remote it names. Less the newline git ends the line with, and nothing else: trimmed, a rule that ends a
    // URL with a space read as no rewrite at all (the gate critic's S9, #410).
    private fun askedAs(url: String): String? =
        git.answer(listOf("ls-remote", "--get-url", url)).takeIf { it.succeeded() }?.stdout?.removeSuffix("\n")

    // Whether a `pushInsteadOf` rule's prefix starts [url]: git rewrites by the longest one that does, so any one is
    // a rewrite. Rules git could not list — an error, or a listing cut off by its timeout — may hold one.
    private fun pushRewritten(url: String): Boolean {
        val rules = git.answer(listOf("config", "--null", "--get-regexp", PUSH_INSTEAD_OF))
        if (!rules.succeeded() && rules.code != NO_KEY_MATCHED) return true
        return prefixesIn(rules.stdout).any { url.startsWith(it) }
    }

    // Each rule's value: with `--null`, a key, a newline and a value, then a NUL. A key never holds a newline, and a
    // base can hold a space, which is what parts them without it (measured). An empty value is a prefix too, and
    // starts every URL (the gate critic's S8, #410): only what follows the last NUL, which is nothing, is no rule.
    private fun prefixesIn(listing: String): List<String> =
        listing.split(NUL).filter { it.isNotEmpty() }.map { it.substringAfter('\n', "") }

    // Whether git knows [url] as a remote's name. `get-url` knows only the remotes of the repository's own config,
    // while `ls-remote` and the push take one of the global config as well, each by its own URL (measured, #410);
    // `git remote` lists both. Remotes git could not list may hold it.
    private fun namesARemote(url: String): Boolean {
        val remotes = git.answer(listOf("remote"))
        return !remotes.succeeded() || url in remotes.stdout.lines()
    }

    private companion object {
        /** Every `url.<base>.pushInsteadOf` rule, as `git config --get-regexp` matches its lowercased key. */
        const val PUSH_INSTEAD_OF = "^url\\..*\\.pushinsteadof$"

        /** `git config --get-regexp` exits 1 when no key matches, which is no error. */
        const val NO_KEY_MATCHED = 1

        const val NUL = '\u0000'
    }
}

/**
 * Where a push goes: the push URLs `git remote get-url` gives for a remote it knows, or a URL or a path a branch
 * names as its remote, which git pushes to as it is (#378). Which one it is decides how [PushRewrites] asks: a push
 * URL equal to its remote's name is still a remote's, and `ls-remote` reads it as one (#410).
 */
internal sealed interface PushDestinations {
    /** Each place the push goes, as `ls-remote` is asked what it holds. */
    val urls: List<String>

    /** A remote `get-url` knows, by the push URLs it gives: `pushurl`, else `url`, each with its rules applied. */
    data class OfRemote(override val urls: List<String>) : PushDestinations

    /** A URL or a path `get-url` knows no remote by. */
    data class OfUrl(val url: String) : PushDestinations {
        override val urls: List<String> get() = listOf(url)
    }
}
