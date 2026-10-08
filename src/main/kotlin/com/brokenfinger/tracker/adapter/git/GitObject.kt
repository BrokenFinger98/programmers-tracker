package com.brokenfinger.tracker.adapter.git

/** One object as `git cat-file` describes it: its id, its type, and its size in bytes (#373). */
internal data class GitObject(val id: String, val type: String, val size: Long) {
    /** The line `cat-file --batch` prints before this object's content. */
    fun header(): String = "$id $type $size"

    companion object {
        /**
         * A `cat-file --batch-check` line, `<id> <type> <size>`, as an object; null for any other line —
         * `<id> missing` among them. Lenient, as everything read back from git is: a line that does not
         * parse is an answer git did not give, and the search that asked refuses on it.
         */
        fun ofReceived(line: String): GitObject? {
            val (id, type, size) = line.split(' ').takeIf { it.size == FIELDS } ?: return null
            if (type !in TYPES) return null
            return size.toLongOrNull()?.takeIf { it >= 0 }?.let { GitObject(id, type, it) }
        }

        private const val FIELDS = 3

        /** Git's four object types: a fifth would be a line this was never written to read. */
        private val TYPES = setOf("blob", "tree", "commit", "tag")
    }
}
