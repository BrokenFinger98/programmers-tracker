package com.brokenfinger.tracker.adapter.store

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * A directory held through a [SecureDirectoryStream] (#374): every file in it is reached from the open directory by one
 * name — `openat`, `renameat`, `unlinkat` — and never through a link, so whatever [path] comes to lead to, a write here
 * lands in this directory, or fails where it is gone (measured: on the host, and in the tracker's image over a macOS
 * bind mount).
 *
 * [path] is what it was opened by, and is used for one thing: making a directory below it, which the JDK cannot do
 * through a handle. What is made is then opened through the handle, so one made through a link swapped in meanwhile is
 * not found there, and nothing is written in it.
 */
internal class SecureDirectoryHandle(private val stream: SecureDirectoryStream<Path>, private val path: Path) :
    DirectoryHandle {
    override fun child(name: String): DirectoryHandle? {
        val attributes = attributesOf(name) ?: return madeThenOpened(name)
        if (!attributes.isDirectory) return null
        return opened(name)
    }

    override fun isAt(directory: Path): Boolean {
        val held = stream.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
        val there = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        return held != null && held == there
    }

    override fun create(name: String, permissions: Set<PosixFilePermission>) {
        stream.newByteChannel(nameIn(name), NEW_FILE, PosixFilePermissions.asFileAttribute(permissions)).close()
    }

    override fun write(name: String, bytes: ByteArray) {
        stream.newByteChannel(nameIn(name), REWRITTEN_FILE).use { it.writeAll(bytes) }
    }

    override fun append(name: String, bytes: ByteArray) {
        stream.newByteChannel(nameIn(name), APPENDED_FILE).use { it.writeAll(bytes) }
    }

    override fun move(name: String, into: DirectoryHandle, target: String): Boolean {
        if (attributesOf(name) == null) return false
        val there = into as SecureDirectoryHandle
        runCatching { renamed(name, there, target) }.getOrElse { overADirectory(name, there, target, it) }
        return true
    }

    override fun delete(name: String): Boolean {
        try {
            stream.deleteFile(nameIn(name))
        } catch (absent: NoSuchFileException) {
            return false
        }
        return true
    }

    override fun permissionsOf(name: String): Set<PosixFilePermission>? {
        val attributes = posixViewOf(name).readAttributes()
        if (!attributes.isRegularFile) return null
        return attributes.permissions()
    }

    override fun setPermissions(name: String, permissions: Set<PosixFilePermission>) {
        posixViewOf(name).setPermissions(permissions)
    }

    override fun close() = stream.close()

    private fun renamed(name: String, there: SecureDirectoryHandle, target: String) =
        stream.move(nameIn(name), there.stream, nameIn(target))

    // `rename(2)` refuses a file over a directory, and a replace by path took an empty one away first: so does this.
    // One holding something fails as the file system fails it.
    private fun overADirectory(name: String, there: SecureDirectoryHandle, target: String, failure: Throwable) {
        if (there.attributesOf(target)?.isDirectory != true) throw failure
        there.stream.deleteDirectory(nameIn(target))
        renamed(name, there, target)
    }

    // No `mkdirat` in the JDK: made by path, then opened through this handle, which finds none made through a link.
    private fun madeThenOpened(name: String): DirectoryHandle {
        createdOrThere(path.resolve(nameIn(name)))
        return opened(name)
    }

    private fun opened(name: String): DirectoryHandle =
        SecureDirectoryHandle(stream.newDirectoryStream(nameIn(name), NOFOLLOW_LINKS), path.resolve(name))

    // What stands at [name], looked at through this handle without following a link; null when nothing does.
    private fun attributesOf(name: String): BasicFileAttributes? {
        try {
            return stream.getFileAttributeView(nameIn(name), BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                .readAttributes()
        } catch (absent: NoSuchFileException) {
            return null
        }
    }

    private fun posixViewOf(name: String): PosixFileAttributeView =
        stream.getFileAttributeView(nameIn(name), PosixFileAttributeView::class.java, NOFOLLOW_LINKS)

    private fun nameIn(name: String): Path = oneName(path, name)
}
