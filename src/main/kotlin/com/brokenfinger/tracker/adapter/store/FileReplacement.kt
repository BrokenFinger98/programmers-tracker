package com.brokenfinger.tracker.adapter.store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions

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
 * Every operation on the target's directory is here, and only here: making the temporary file, writing it, setting its
 * mode, the move, the clean-up. Writing through a directory handle rather than a path (#374) changes this class and
 * the walk that hands it a directory ([RecordBound]), and none of the writers of a page, a note or a state document.
 */
internal class FileReplacement(private val mode: FileMode) {
    /** Replaces [target] whole with [text], written beside it and moved over it. */
    fun replace(target: Path, text: String) {
        val temp = temporaryBeside(target)
        runCatching { writtenThenMoved(temp, target, text) }.onFailure {
            Files.deleteIfExists(temp)
            throw it
        }
    }

    /** The temporary file a replace of [target] writes first: beside it, named after it, in its mode from the start. */
    fun temporaryBeside(target: Path): Path {
        val absolute = target.toAbsolutePath()
        return Files.createTempFile(absolute.parent, ".${absolute.fileName}.", TEMP_SUFFIX, *mode.atCreation(absolute))
    }

    private fun writtenThenMoved(temp: Path, target: Path, text: String) {
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        mode.kept(target, temp)
        moved(temp, target)
    }

    // ATOMIC_MOVE is the guarantee wanted; where a filesystem cannot give it, a plain replace still follows no link.
    private fun moved(temp: Path, target: Path) {
        runCatching { Files.move(temp, target, ATOMIC_MOVE) }.getOrElse { Files.move(temp, target, REPLACE_EXISTING) }
    }

    companion object {
        /**
         * What the name of every temporary file a replace makes ends with, and nothing else's (#386). A process killed
         * between the write and the move leaves the temporary file behind, and a reconcile racing a live write sees one,
         * so git's reconciliation leaves out every hidden file with this ending; the name has to be the tracker's own,
         * or a file of the owner's would be left out with it.
         */
        const val TEMP_SUFFIX = ".programmers-tracker.tmp"
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

    /** The mode [target]'s temporary file is made in. */
    fun atCreation(target: Path): Array<FileAttribute<*>> {
        if (POSIX !in target.fileSystem.supportedFileAttributeViews()) return emptyArray()
        if (plainWhenNew) return arrayOf(PLAIN)
        return arrayOf(OWNER)
    }

    /**
     * Hands a regular file's own mode on to [temp], where this mode keeps one: a file rewritten in place kept its mode.
     * A link's own bits say nothing about a file. Best effort: a mode that cannot be set leaves [temp] as it was made.
     */
    fun kept(target: Path, temp: Path) {
        if (!keepsRegularFiles) return
        val current = runCatching { Files.readAttributes(target, PosixFileAttributes::class.java, NOFOLLOW_LINKS) }
        val attributes = current.getOrNull()?.takeIf { it.isRegularFile } ?: return
        runCatching { Files.setPosixFilePermissions(temp, attributes.permissions()) }
    }

    companion object {
        private const val POSIX = "posix"
        private val PLAIN = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw-rw-"))
        private val OWNER = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

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
