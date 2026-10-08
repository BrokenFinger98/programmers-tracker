package com.brokenfinger.tracker.adapter.git

/**
 * What a push would not send again (#376): the tips its destinations hold — as each destination says
 * itself, through `git ls-remote` — that this repository holds too.
 *
 * Not the remote-tracking refs. They say what a remote held when it was last fetched, and they outlive
 * the remote: re-created under the same name, or its URL changed with `git remote set-url`, the old refs
 * still vouched for commits the new destination never received, and a token pushed once went to it
 * unsearched. Nor `ls-remote` of the remote's name, which asks its fetch URL while the push goes to its
 * push URL; the caller hands in the push URLs.
 *
 * A tip counts only when every destination holds it — a push with several URLs sends each what it lacks
 * — and when this repository holds it as well: one it lacks cannot stand in a range, and is not what a
 * push from here sends. Excluding fewer tips searches more, which is safe.
 *
 * Fails closed: a destination that cannot be asked, an answer that is not a list of refs, and a
 * description of the tips that fails or does not cover each of them are each null — unknown — never
 * "nothing to exclude". What `ls-remote` prints is never logged.
 */
internal class RemoteTips(private val git: GitCalls) {
    /** The tips every one of [destinations] holds and this repository has; null when that cannot be told. */
    fun heldByAll(destinations: List<String>): Set<String>? {
        val advertised = destinations.map { advertisedAt(it) ?: return null }
        return heldHere(advertised.reduceOrNull { all, each -> all intersect each } ?: return null)
    }

    // The ids [destination] advertises; null when it cannot be asked or answers anything but a list of refs.
    private fun advertisedAt(destination: String): Set<String>? {
        val answer = git.answer(listOf("ls-remote", destination))
        if (!answer.succeeded()) return null
        return answer.stdout.lines().filter { it.isNotEmpty() }.map { idOf(it) ?: return null }.toSet()
    }

    // Of [ids], those this repository holds, asked of all at once; null unless git described each, in order.
    private fun heldHere(ids: Set<String>): Set<String>? {
        if (ids.isEmpty()) return ids
        val asked = ids.toList()
        val answer = git.answer(listOf("cat-file", "--batch-check"), asked.joinToString("\n", postfix = "\n"))
        if (!answer.succeeded()) return null
        val lines = answer.stdout.lines().filter { it.isNotEmpty() }
        if (lines.map { it.substringBefore(' ') } != asked) return null
        return lines.mapNotNull { GitObject.ofReceived(it)?.id }.toSet()
    }

    private companion object {
        /** `<id><TAB><ref>`, as `ls-remote` prints each ref: a SHA-1 or SHA-256 id. */
        val ADVERTISED = Regex("""(?<id>[0-9a-f]{40}|[0-9a-f]{64})\t\S.*""")

        fun idOf(line: String): String? = ADVERTISED.matchEntire(line)?.groups?.get("id")?.value
    }
}
