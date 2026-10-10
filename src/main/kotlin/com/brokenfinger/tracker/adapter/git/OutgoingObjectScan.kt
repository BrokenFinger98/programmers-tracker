package com.brokenfinger.tracker.adapter.git

import java.io.InputStream

/**
 * The push half of the content gate (#360): every object a push would send, read once and searched for
 * GitHub's token shapes and the stored values (#373).
 *
 * `git grep` over each outgoing commit read every commit's whole tree, so a first push read each unchanged
 * file once per commit: 252 s for 5,000 commits, one call 16 s, on a history shaped like the #360 review's;
 * this scan took 0.5 s there. Here `rev-list --objects` names each object of each listing once, the first
 * time it reaches it — the push lists its range and HEAD's own tree; `cat-file --batch-check` tells each
 * one's type and size; and `cat-file --batch` prints the blobs, the commits and the trees, in calls of about
 * [bytesPerCall] of content each, which [BatchOutput] searches as it reads them — a commit's message apart
 * from its header, a tree for its names (#375). An object many commits share, or both listings name, is read
 * once.
 *
 * Fails closed: a listing, a description or a read that fails or does not finish in time, an object git
 * cannot describe or print, and output other than what was asked for are each [SearchOutcome.Unsearched].
 */
internal class OutgoingObjectScan(
    private val git: GitCalls,
    private val bytesPerCall: Long = BYTES_PER_CALL,
    private val window: Int = BatchOutput.WINDOW,
) {
    /**
     * Searches every object [listings] name — each the revisions of one `rev-list --objects` — for the token
     * shapes and [stored]'s values. What two listings name is read once. A name in a tree that [held] already
     * holds is not new (#402).
     */
    fun outcome(
        listings: List<List<String>>,
        stored: StoredCredential,
        held: NamesHeld = NamesHeld.NONE,
    ): SearchOutcome {
        val listed = listings.map { listed(it) ?: return SearchOutcome.Unsearched }.flatten().distinct()
        val read = described(listed)?.filter { it.type in READ_TYPES } ?: return SearchOutcome.Unsearched
        val patterns = TokenPatterns.of(stored)
        val outcomes = calls(read).asSequence().map { searched(it, patterns, held) }
        return outcomes.firstOrNull { it != SearchOutcome.Clean } ?: SearchOutcome.Clean
    }

    // Every object one listing names, each once: rev-list prints an object the first time it reaches it, and a
    // path after the id of each tree and blob, which only orders them. The listing goes on stdin: a push past a
    // remote of many refs leaves each one's tip out, and on the command line about 800 of them filled the 32,767
    // characters Windows allows (#405). A line there that is not a [REVISION] is refused before git is asked.
    private fun listed(range: List<String>): List<String>? {
        if (!range.all { REVISION.matches(it) }) return null
        val answer = git.answer(listOf("rev-list", "--objects", "--stdin"), linesOf(range))
        if (!answer.succeeded()) return null
        return answer.stdout.lines().filter { it.isNotEmpty() }.map { it.substringBefore(' ') }
    }

    // Each object's type and size, [IDS_PER_CALL] to a call; null unless git described every one, in order.
    private fun described(ids: List<String>): List<GitObject>? =
        ids.chunked(IDS_PER_CALL).map { describedInOneCall(it) ?: return null }.flatten()

    private fun describedInOneCall(ids: List<String>): List<GitObject>? {
        val answer = git.answer(listOf("cat-file", "--batch-check", "--buffer"), linesOf(ids))
        if (!answer.succeeded()) return null
        val objects = answer.stdout.lines().filter { it.isNotEmpty() }.map { GitObject.ofReceived(it) ?: return null }
        return objects.takeIf { described -> described.map { it.id } == ids }
    }

    // The objects to read, a call each: those whose content starts in the same [bytesPerCall] of the whole, so a
    // call reads that much and at most one object past it, and never more than [IDS_PER_CALL] of them.
    private fun calls(objects: List<GitObject>): List<List<GitObject>> {
        val starts = objects.runningFold(0L) { start, each -> start + each.size }
        val stretches = objects.withIndex().groupBy({ starts[it.index] / bytesPerCall }, { it.value })
        return stretches.values.flatMap { it.chunked(IDS_PER_CALL) }
    }

    private fun searched(call: List<GitObject>, patterns: TokenPatterns, held: NamesHeld): SearchOutcome {
        val read = git.streamed(listOf("cat-file", "--batch", "--buffer"), linesOf(call.map { it.id })) {
            BatchOutput(it, patterns, window, held).searched(call)
        }
        return read ?: SearchOutcome.Unsearched
    }

    companion object {
        /**
         * Content per `cat-file --batch` call. A call of this size took 0.4–1.0 s, the search of its output
         * included, on a history whose every commit added to a growing log (#373) — far inside
         * [GitProcess.TIMEOUT]. Its output waits in a temporary file of about this size until it is read.
         */
        const val BYTES_PER_CALL = 64L shl 20

        /** Ids per call: on stdin, so no system's argument limit applies; this keeps each call's input small. */
        private const val IDS_PER_CALL = 50_000

        /**
         * What is read: blobs; commits, which hold a message, an author and a committer; and trees, which hold
         * the names (#375). A tag is never listed: the push names one branch, and sends no tag.
         */
        private val READ_TYPES = setOf("blob", "commit", "tree")

        /**
         * A line `rev-list --stdin` reads as a revision, and nothing else: not an option, which git before 2.42
         * refuses there — a `--not` line was "fatal: options not supported in --stdin mode" on 2.39.5, and every
         * commit and push was refused (the gate critic's M1 on #410, measured); not an empty line, which ends the
         * list with exit 0, so what came after it was never listed (2.39.5 and 2.48.1, measured); and no newline,
         * which would make two. A listing leaves an object out as `^<id>`.
         */
        private val REVISION = Regex("[^-\\n][^\\n]*")

        private fun linesOf(ids: List<String>): String = ids.joinToString("\n", postfix = "\n")
    }
}

/** How the scan runs git: a call whose answer is read whole, and one whose answer is read as a stream. */
internal interface GitCalls {
    /** One call, its answer read whole — for a list of ids, which grows with their number and not their size. */
    fun answer(args: List<String>, input: String? = null): GitResult

    /** One call, its stdout handed to [read]; null, and [read] never called, unless git exited 0 in time. */
    fun <T : Any> streamed(args: List<String>, input: String?, read: (InputStream) -> T): T?
}

/** [GitCalls] through [process], each command made by [command]: `git`, anything before the arguments, and them. */
internal class ProcessCalls(private val process: GitProcess, private val command: (List<String>) -> List<String>) :
    GitCalls {
    override fun answer(args: List<String>, input: String?): GitResult = process.run(command(args), input)

    override fun <T : Any> streamed(args: List<String>, input: String?, read: (InputStream) -> T): T? =
        process.runReading(command(args), input, read)
}
