package com.brokenfinger.tracker.adapter.store

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * The one way a whole file is written in the records repository and in its state directory alike (#386): into a
 * temporary file beside its target, then moved over the target. [RecordWrites] and [AtomicStateFile] each kept a copy
 * of this; they now say only what is theirs — the walk, the refusals, the guard — and hand the write to this.
 *
 * **Beside, and moved.** The temporary file is made in the target's own directory, so the move is a rename within one
 * directory and one filesystem: a reader sees the old file whole or the new one whole. A rename replaces whatever
 * stands at the target — a link, a file with another name, an empty directory — rather than writing through it, and a
 * directory that holds something fails it, as the filesystem fails it. Anything that fails once the temporary file
 * exists takes it away again.
 *
 * **In a mode set when the file is made** ([FileMode]), so a file that must be owner-only never sits in a wider one
 * first. A regular file's own mode is kept where the mode says so; a link's own bits never are.
 *
 * **Through the directory held** (#374). Every operation on the target's directory — making the temporary file,
 * writing it, setting its mode, the move, the clean-up — goes through a [DirectoryHandle], by name. A writer of state
 * hands it `.ps` held open from before its check, so a link swapped in for `.ps` meanwhile is never followed; a path
 * is held by path, so the records and every document outside `.ps` are written exactly as before.
 */
internal class FileReplacement(private val mode: FileMode) {
    /** Replaces [target] whole with [text], written beside it and moved over it, its directory held by path. */
    fun replace(target: Path, text: String) {
        val absolute = target.toAbsolutePath()
        PathDirectoryHandle(absolute.parent).use { replace(it, absolute.fileName.toString(), text) }
    }

    /**
     * Replaces [name] in [directory] whole with [text], written beside it and moved over it, through the handle. What
     * stopped the write is what is thrown: a clean-up that fails too is kept on it as suppressed, never thrown in its
     * place (#386's review).
     */
    fun replace(directory: DirectoryHandle, name: String, text: String) {
        val temp = temporaryIn(directory, name)
        runCatching { writtenThenMoved(directory, temp, name, text) }.onFailure { failure ->
            runCatching { directory.delete(temp) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    /** The temporary file a replace of [target] writes first: beside it, named after it, in its mode from the start. */
    fun temporaryBeside(target: Path): Path {
        val absolute = target.toAbsolutePath()
        val temp = PathDirectoryHandle(absolute.parent).use { temporaryIn(it, absolute.fileName.toString()) }
        return absolute.resolveSibling(temp)
    }

    // Hidden, named after its target and ending as only the tracker's own do; made in its mode where nothing stands.
    private fun temporaryIn(directory: DirectoryHandle, name: String): String {
        val temp = ".$name.${random.nextLong().toULong()}$TEMP_SUFFIX"
        directory.create(temp, mode.atCreation())
        return temp
    }

    private fun writtenThenMoved(directory: DirectoryHandle, temp: String, name: String, text: String) {
        directory.write(temp, text.toByteArray(StandardCharsets.UTF_8))
        mode.kept(directory, name, temp)
        directory.move(temp, directory, name)
    }

    companion object {
        /**
         * What the name of every temporary file a replace makes ends with, and nothing else's (#386). A process killed
         * between the write and the move leaves the temporary file behind, and a reconcile racing a live write sees one,
         * so git's reconciliation leaves out every hidden file with this ending; the name has to be the tracker's own,
         * or a file of the owner's would be left out with it.
         */
        const val TEMP_SUFFIX = ".programmers-tracker.tmp"

        // As `Files.createTempFile` names one: a name nobody can guess and plant something at first.
        private val random = SecureRandom()
    }
}

/**
 * The mode a replaced file gets (#386): a new file's, set as its temporary file is made, and whether a regular file's
 * own is kept. Where a filesystem has no POSIX modes, as on Windows, a file gets what its directory gives a new one.
 */
internal enum class FileMode(private val plainWhenNew: Boolean, private val keepsRegularFiles: Boolean) {
    /** A record file: a regular file's own mode kept, and a new one what a plain write gives it, which the umask narrows. */
    KEPT_ELSE_PLAIN(plainWhenNew = true, keepsRegularFiles = true),

    /** Code files and the state the server keeps: owner-only, whatever someone widened the file it replaces to. */
    OWNER_ONLY(plainWhenNew = false, keepsRegularFiles = false),

    /** A document someone else made, the owner's `.gitignore`: its own mode kept, and owner-only were it new. */
    KEPT_ELSE_OWNER_ONLY(plainWhenNew = false, keepsRegularFiles = true),
    ;

    /** The mode a temporary file is made in, where its filesystem keeps one. */
    fun atCreation(): Set<PosixFilePermission> {
        if (plainWhenNew) return PLAIN
        return OWNER
    }

    /**
     * Hands the regular file [name]'s own mode on to [temp], both in [directory], where this mode keeps one: a file
     * rewritten in place kept its mode. A link's own bits say nothing about a file. Best effort: a mode that cannot be
     * read or set leaves [temp] as it was made.
     */
    fun kept(directory: DirectoryHandle, name: String, temp: String) {
        if (!keepsRegularFiles) return
        val permissions = runCatching { directory.permissionsOf(name) }.getOrNull() ?: return
        runCatching { directory.setPermissions(temp, permissions) }
    }

    companion object {
        private val PLAIN = PosixFilePermissions.fromString("rw-rw-rw-")
        private val OWNER = PosixFilePermissions.fromString("rw-------")

        /** A record writer's mode: owner-only where it asks, as code files are, and otherwise a regular file's own kept. */
        fun forRecords(ownerOnly: Boolean): FileMode {
            if (ownerOnly) return OWNER_ONLY
            return KEPT_ELSE_PLAIN
        }

        /** A state file's mode: owner-only, unless it is a document someone else made that keeps its own. */
        fun forState(keepsPermissions: Boolean): FileMode {
            if (keepsPermissions) return KEPT_ELSE_OWNER_ONLY
            return OWNER_ONLY
        }
    }
}
