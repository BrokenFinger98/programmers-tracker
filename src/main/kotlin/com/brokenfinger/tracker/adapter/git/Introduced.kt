package com.brokenfinger.tracker.adapter.git

/**
 * What a commit adds to a tree (#376): the blobs it leaves at a path that held something else, and the paths
 * it adds — read from git's raw diff output, `diff-index --cached -z` for a commit about to be made and
 * `diff-tree -r -z` for what entered HEAD, which name both.
 *
 * A path a commit changes or removes keeps the names the tree already gave it, so only the paths it adds can add
 * names, and each part of one is a name; and a blob is searched less what the tree it is added to holds anywhere,
 * so content a commit moves or copies within it is not added either. What the tree already holds is a leak to
 * revoke if it carries a token, not something this commit adds — a name as much as content (#402).
 */
internal class Introduced private constructor(
    private val blobs: List<String>,
    private val names: List<String>,
    private val base: String,
) : Preview {
    /**
     * The blobs as `rev-list` listings, each leaving out what [base] holds: `rev-list --objects` names a blob given
     * to it as itself, and none that `^<base>`'s tree holds. A listing goes on stdin, where git before 2.42 reads no
     * `--not`, and every commit on 2.39.5 was refused (the gate critic's M1 on #410, measured). [IDS_PER_LISTING] to
     * a list.
     */
    fun listings(): List<List<String>> = blobs.chunked(IDS_PER_LISTING).map { it + "^$base" }

    /**
     * The names the paths it adds hold — each part of each — matched as their bytes and never as UTF-16: a name
     * holds no NUL, so no UTF-16 text of an ASCII character can be in one (#375 reads a tree's names so). One that
     * carries a token is [SearchOutcome.FoundInName] unless [held] holds it already, the base's own: a file added
     * under a directory a pull named with a token adds its own name, not the directory's (#402).
     */
    fun namesSearched(patterns: TokenPatterns, held: NamesHeld): SearchOutcome {
        val heldOrNot = names.filter(patterns::foundInBytes).map { held.holds(it) ?: return SearchOutcome.Unsearched }
        return SearchOutcome.FoundInName.takeIf { false in heldOrNot } ?: SearchOutcome.Clean
    }

    /**
     * One change in raw diff output — `:<old mode> <new mode> <old id> <new id> <status>` — and its path, in
     * the bytes git holds, which [GitProcess] reads as UTF-8.
     */
    private class Change(private val mode: String, private val id: String, private val adds: Boolean, path: String) {
        /**
         * The path as its bytes, one character each, as `TokenPatterns` searches them. A path git printed in
         * bytes that are not UTF-8 came back with U+FFFD in their place, and neither a token shape nor a
         * stored value, whose bytes are UTF-8, can match where they stood.
         */
        private val name = String(path.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

        /** The blob the change leaves at its path: none for a removal, and none for a submodule's commit. */
        fun blobLeft(): String? = id.takeIf { mode in BLOB_MODES }

        fun nameAdded(): String? = name.takeIf { adds }
    }

    companion object {
        /**
         * Ids to a `rev-list` call. They go on its stdin since #405, so no command line's limit applies; this keeps
         * each call's input small.
         */
        const val IDS_PER_LISTING = 500

        /**
         * Read from raw `-z` output: a header and a path for each change, each ending in NUL. Null when it is
         * not that, so a search of what could not be read refuses rather than passes.
         */
        fun ofReceived(answer: String, base: String): Introduced? {
            val changes = changesIn(answer) ?: return null
            val names = changes.mapNotNull { it.nameAdded() }.flatMap { it.split('/') }.distinct()
            return Introduced(changes.mapNotNull { it.blobLeft() }, names, base)
        }

        private fun changesIn(answer: String): List<Change>? {
            if (answer.isEmpty()) return emptyList()
            if (!answer.endsWith(NUL)) return null
            val pairs = answer.dropLast(1).split(NUL).chunked(2)
            if (pairs.last().size != 2) return null
            return pairs.map { (header, path) -> changeOf(header, path) ?: return null }
        }

        private fun changeOf(header: String, path: String): Change? {
            val (mode, id, status) = HEADER.matchEntire(header)?.destructured ?: return null
            return Change(mode, id, status == ADDED, path)
        }

        private const val NUL = '\u0000'

        private val HEADER = Regex(""":[0-7]{6} ([0-7]{6}) [0-9a-f]{40,64} ([0-9a-f]{40,64}) ([A-Z])[0-9]*""")

        /** A file, an executable file, and a link, which git stores as a blob of the path it holds. */
        private val BLOB_MODES = setOf("100644", "100755", "120000")

        /** The status of a path the change adds; with renames off, a moved file's new path is one. */
        private const val ADDED = "A"
    }
}
