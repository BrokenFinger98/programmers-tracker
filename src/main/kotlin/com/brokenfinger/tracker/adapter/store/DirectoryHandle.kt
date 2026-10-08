package com.brokenfinger.tracker.adapter.store

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * A directory opened once and written in by name (#374): what a writer of state holds between the check of a directory
 * and its write.
 *
 * [StateDirectory] checks `.ps` and the directories a writer uses below it, and the writer then wrote to a path naming
 * them, resolved again at the write. A link swapped in between — by a pull, while git was being asked — led the write
 * wherever it pointed, a directory git tracks included (N10 in the review of #360: measured, the timers document landed
 * in `problems/`). A handle is the directory itself, not its name. Every file is reached from it, one name at a time
 * and never through a link, so whatever the path comes to lead to after the check, a write lands in the directory that
 * was checked, or fails where that directory is gone.
 *
 * The JDK gives one as a [SecureDirectoryStream] — `openat`, `renameat`, `unlinkat` — where the platform has them:
 * Linux and macOS, and the tracker's image over a macOS bind mount (measured on all three). Windows has none, and there
 * a directory is held by its path and written in as before ([DirectoryHandles]). Both keep one contract, which
 * `DirectoryHandleTest` runs against each: only where a write goes after such a swap differs.
 *
 * **One name, never a path.** An absolute name leaves the directory behind, and a second segment is reached through
 * whatever stands at the first, link or not (measured): a name with either is refused.
 *
 * **No directory is made through a handle.** The JDK has no `mkdirat`, so a directory absent below one is made by path
 * and then opened through the handle: a link swapped in meanwhile receives at most an empty directory, and the open
 * fails.
 */
interface DirectoryHandle : AutoCloseable {
    /**
     * The directory [name] in this one, made first when absent, and opened through this one without following a link;
     * null when what stands there is not a directory.
     */
    fun child(name: String): DirectoryHandle?

    /** Whether [directory], looked at without following a link, is the directory held here. */
    fun isAt(directory: Path): Boolean

    /**
     * Whether something that is not a regular file stands at [name] — a link, a directory, a FIFO — looked at without
     * following a link and never opened; false where nothing does, or where that cannot be told, as `Files.exists`
     * answers.
     */
    fun isThereButNotAFile(name: String): Boolean

    /** A new, empty file [name], in [permissions] where the file system keeps a mode; never made where anything stands. */
    fun create(name: String, permissions: Set<PosixFilePermission>)

    /** The existing file [name], holding [bytes] and nothing else; never written through a link. */
    fun write(name: String, bytes: ByteArray)

    /** [bytes] at the end of the file [name], made when absent; never written through a link. */
    fun append(name: String, bytes: ByteArray)

    /**
     * [name] renamed [target] in [into], held the same way: whatever stands there — a link, an empty directory — is
     * replaced and never written through, and a directory holding something fails the move. False when there is no
     * [name].
     */
    fun move(name: String, into: DirectoryHandle, target: String): Boolean

    /** [name] deleted, a link as a link; false when nothing was there. */
    fun delete(name: String): Boolean

    /** The mode of the regular file [name], looked at without following a link; null when [name] is anything else. */
    fun permissionsOf(name: String): Set<PosixFilePermission>?

    /** [permissions] given to the file [name]. */
    fun setPermissions(name: String, permissions: Set<PosixFilePermission>)
}

/**
 * How a directory is opened to be written in (#374): through a [SecureDirectoryStream] where the file system gives one,
 * by its path where it does not. Windows gives none, and there state is written by path as it always was: a link
 * swapped in between a check and a write is followed, which is said once, when the platform's are chosen.
 */
fun interface DirectoryHandles {
    /** [directory] opened by its path — through any link on the way, as a path is — and held as it was then. */
    fun open(directory: Path): DirectoryHandle

    companion object {
        /** Every directory held by its path. */
        val BY_PATH: DirectoryHandles = DirectoryHandles(::PathDirectoryHandle)

        /** Every directory held through a handle, and by its path only where its file system gives none. */
        val THROUGH_A_HANDLE: DirectoryHandles = DirectoryHandles(::throughAHandle)

        /**
         * Chosen when first asked for, as the composition root's state directory is built at startup: through a handle
         * where this platform gives one, or cannot say. `DirectoryHandleTest` pins which do, on every platform CI runs.
         */
        val ON_THIS_PLATFORM: DirectoryHandles by lazy { selected(givesHandles(temporaryDirectory())) }

        private const val TMPDIR = "java.io.tmpdir"
        private const val BY_PATH_ONLY =
            "This platform's file system gives no directory handle (java.nio's SecureDirectoryStream), as " +
                "Windows does not: state under .ps is written by path, so a link swapped in for a directory between " +
                "its check and the write is followed (#374). Said once."

        private val logger = LoggerFactory.getLogger(DirectoryHandles::class.java)

        /** [THROUGH_A_HANDLE] where the platform [givesHandles]; [BY_PATH] where not, which is said. */
        internal fun selected(givesHandles: Boolean): DirectoryHandles {
            if (givesHandles) return THROUGH_A_HANDLE
            logger.warn(BY_PATH_ONLY)
            return BY_PATH
        }

        // Any directory on the default file system answers for all of it; the JVM's own temporary one is always there.
        private fun temporaryDirectory(): Path = Path.of(System.getProperty(TMPDIR))

        /**
         * Whether [directory]'s file system gives a [SecureDirectoryStream] for it — yes where it cannot say, since
         * [THROUGH_A_HANDLE] holds by path any directory whose file system gives none: not knowing must never cost a
         * handle where there is one, nor be said as a platform that gives none.
         */
        internal fun givesHandles(directory: Path): Boolean = runCatching {
            Files.newDirectoryStream(directory).use { it is SecureDirectoryStream<*> }
        }.getOrDefault(true)
    }
}

// The stream the file system gives, held when it is a secure one; closed, and the path held, when it is not.
private fun throughAHandle(directory: Path): DirectoryHandle {
    val stream = Files.newDirectoryStream(directory)
    if (stream is SecureDirectoryStream<Path>) return SecureDirectoryHandle(stream, directory)
    stream.close()
    return PathDirectoryHandle(directory)
}

/**
 * A directory held by its path (#374): every write in it goes where [directory] leads when the write is made. What
 * Windows is left with, and the same contract as a handle in every other way.
 */
internal class PathDirectoryHandle(private val directory: Path) : DirectoryHandle {
    override fun child(name: String): DirectoryHandle? {
        val child = fileOf(name)
        if (!isThere(child)) createdOrThere(child)
        if (!Files.isDirectory(child, NOFOLLOW_LINKS)) return null
        return PathDirectoryHandle(child)
    }

    override fun isAt(directory: Path): Boolean = this.directory == directory

    override fun isThereButNotAFile(name: String): Boolean {
        val file = fileOf(name)
        return Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)
    }

    override fun create(name: String, permissions: Set<PosixFilePermission>) {
        Files.newByteChannel(fileOf(name), NEW_FILE, *modeOf(permissions)).close()
    }

    override fun write(name: String, bytes: ByteArray) {
        Files.newByteChannel(fileOf(name), REWRITTEN_FILE).use { it.writeAll(bytes) }
    }

    override fun append(name: String, bytes: ByteArray) {
        Files.newByteChannel(fileOf(name), APPENDED_FILE).use { it.writeAll(bytes) }
    }

    override fun move(name: String, into: DirectoryHandle, target: String): Boolean {
        val source = fileOf(name)
        if (!isThere(source)) return false
        moved(source, (into as PathDirectoryHandle).fileOf(target))
        return true
    }

    override fun delete(name: String): Boolean = Files.deleteIfExists(fileOf(name))

    override fun permissionsOf(name: String): Set<PosixFilePermission>? {
        val attributes = Files.readAttributes(fileOf(name), PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) return null
        return attributes.permissions()
    }

    override fun setPermissions(name: String, permissions: Set<PosixFilePermission>) {
        Files.setPosixFilePermissions(fileOf(name), permissions)
    }

    override fun close() = Unit

    private fun fileOf(name: String): Path = directory.resolve(oneName(directory, name))

    // A mode where the file system keeps POSIX ones; elsewhere, as on Windows, what the directory gives a new file.
    private fun modeOf(permissions: Set<PosixFilePermission>): Array<FileAttribute<*>> {
        if (POSIX !in directory.fileSystem.supportedFileAttributeViews()) return emptyArray()
        return arrayOf(PosixFilePermissions.asFileAttribute(permissions))
    }

    // ATOMIC_MOVE is the guarantee wanted; where a file system cannot give it, a plain replace still follows no link,
    // and takes an empty directory away first.
    private fun moved(source: Path, target: Path) {
        runCatching { Files.move(source, target, ATOMIC_MOVE) }
            .getOrElse { Files.move(source, target, REPLACE_EXISTING) }
    }

    private companion object {
        const val POSIX = "posix"
    }
}

/**
 * A new file, never made where anything stands, a link included: `CREATE_NEW` is `O_EXCL`, which no link passes, as
 * `Files.createTempFile` makes one. No `NOFOLLOW_LINKS` beside it, which adds nothing to it and which a file system
 * without links, such as a zip one, refuses.
 */
internal val NEW_FILE: Set<OpenOption> = setOf(CREATE_NEW, WRITE)

/** An existing file, emptied and written, never through a link. */
internal val REWRITTEN_FILE: Set<OpenOption> = setOf(WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS)

/** A file written at its end, made when absent, never through a link. */
internal val APPENDED_FILE: Set<OpenOption> = setOf(CREATE, APPEND, WRITE, NOFOLLOW_LINKS)

/**
 * [name] as one name in [directory]: never absolute or rooted, which would leave the directory behind, and never two
 * segments, the second of which is reached through whatever stands at the first.
 */
internal fun oneName(directory: Path, name: String): Path {
    val path = directory.fileSystem.getPath(name)
    require(path.nameCount == 1 && path.root == null && name !in NOT_NAMES) { "not one name in a directory: $name" }
    return path
}

private val NOT_NAMES = setOf("", ".", "..")

/** Every byte of [bytes], as one write of a channel may take fewer than it is given. */
internal fun SeekableByteChannel.writeAll(bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) write(buffer)
}
