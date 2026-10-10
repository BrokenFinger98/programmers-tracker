package com.brokenfinger.tracker.adapter.git

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The file and directory names a destination already holds, as a search asks about them (#402). A tree carries
 * every name in its directory, so a file added beside a name the destination holds sends that name again; and a
 * name it holds is not new, wherever it stands.
 */
internal interface NamesHeld {
    /** Whether [name], one that carries a token, is held already, as its bytes; null when that cannot be told. */
    fun holds(name: String): Boolean?

    /** That [name], held already and carrying a token, goes out again. */
    fun sent(name: String) = Unit

    companion object {
        /** Nothing held: every name is new, as #375 read them all. */
        val NONE: NamesHeld = object : NamesHeld {
            override fun holds(name: String): Boolean = false
        }
    }
}

/**
 * The names [tips] hold: every entry's name, at any depth, in each one's tree — for a push, the tips its
 * destinations said they hold, through `ls-remote` ([RemoteTips], #376), never a remote-tracking ref; for a
 * commit, HEAD's tree. Held is the name, not where it stands: a directory renamed sends names the destination
 * holds under a new path, and they are not new to it.
 *
 * Listed only when a name that carries a token is asked about, which a search seldom does, and then once: a
 * `cat-file --batch-check` peels the tips to their trees, and an `ls-tree` lists each tree's names as bytes. A
 * tip that is no commit, tag or tree holds none. Git that cannot say makes every answer null.
 */
internal class HeldNames(private val git: GitCalls, private val tips: Set<String>) : NamesHeld {
    private val names: Set<String>? by lazy { listed() }

    private val sentAgain = ConcurrentLinkedQueue<String>()

    override fun holds(name: String): Boolean? = names?.contains(name)

    override fun sent(name: String) {
        sentAgain += name
    }

    /** The names a search found held and carrying a token, in the order they went out again. */
    fun sentAgain(): List<String> = sentAgain.toList()

    private fun listed(): Set<String>? {
        if (tips.isEmpty()) return emptySet()
        val trees = treesOf(tips.sorted()) ?: return null
        return trees.flatMap { namesIn(it) ?: return null }.toSet()
    }

    // Each tip's tree, as one call peels them all; a tip with no tree answers `missing`, and holds no name.
    private fun treesOf(asked: List<String>): Set<String>? {
        val answer = git.answer(listOf("cat-file", "--batch-check"), asked.joinToString("") { "$it^{tree}\n" })
        if (!answer.succeeded()) return null
        val lines = answer.stdout.lines().filter { it.isNotEmpty() }
        if (lines.size != asked.size) return null
        return lines.mapNotNull { TREE.matchEntire(it)?.groups?.get("id")?.value }.toSet()
    }

    // Every name in [tree], at any depth, as its bytes: the last part of each path `ls-tree` prints.
    private fun namesIn(tree: String): List<String>? {
        val listing = git.streamed(LIST_NAMES + tree, null) { it.readAllBytes() } ?: return null
        val paths = String(listing, Charsets.ISO_8859_1).split(NUL).filter { it.isNotEmpty() }
        return paths.map { it.substringAfterLast('/') }
    }

    private companion object {
        val TREE = Regex("""(?<id>[0-9a-f]{40}|[0-9a-f]{64}) tree \d+""")

        /** Every entry at any depth, trees as well (`-t`), each a whole path ending in a NUL, from the tree's root. */
        val LIST_NAMES = listOf("ls-tree", "-r", "-t", "-z", "--name-only", "--full-tree")

        const val NUL = '\u0000'
    }
}
