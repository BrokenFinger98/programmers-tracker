package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.store.DirectoryHandle
import com.brokenfinger.tracker.adapter.store.DirectoryHandles
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

// Object mother for the race a directory handle closes (dev rules §6.4, #374).

/**
 * Directory handles that run [swap] once, between the last check and the first write made through any of them — where
 * a pull can swap a directory for a link (N10 in the review of #360). Every handle they open is watched, and every one
 * opened below those; opening and looking run untouched, so every check a writer makes has passed when [swap] runs.
 */
class SwappingBeforeTheWrite(private val handles: DirectoryHandles, private val swap: () -> Unit) : DirectoryHandles {
    /** Whether [swap] has run, which a write through one of these handles makes it do. */
    var swapped = false
        private set

    override fun open(directory: Path): DirectoryHandle = Watched(handles.open(directory))

    private fun beforeAWrite() {
        if (swapped) return
        swapped = true
        swap()
    }

    private inner class Watched(val held: DirectoryHandle) : DirectoryHandle by held {
        override fun child(name: String): DirectoryHandle? = held.child(name)?.let(::Watched)

        override fun create(name: String, permissions: Set<PosixFilePermission>) {
            beforeAWrite()
            held.create(name, permissions)
        }

        override fun write(name: String, bytes: ByteArray) {
            beforeAWrite()
            held.write(name, bytes)
        }

        override fun append(name: String, bytes: ByteArray) {
            beforeAWrite()
            held.append(name, bytes)
        }

        override fun move(name: String, into: DirectoryHandle, target: String): Boolean {
            beforeAWrite()
            return held.move(name, (into as Watched).held, target)
        }

        override fun delete(name: String): Boolean {
            beforeAWrite()
            return held.delete(name)
        }
    }
}
