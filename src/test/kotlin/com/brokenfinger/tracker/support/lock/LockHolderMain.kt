package com.brokenfinger.tracker.support.lock

import com.brokenfinger.tracker.adapter.store.RecordRepositoryLock
import com.brokenfinger.tracker.adapter.store.RecordRepositoryLockedException
import java.nio.file.Path

/**
 * The child JVM [LockHolder] starts. Takes the lock on the record repository named by its
 * only argument, says whether it got it, and then blocks until stdin closes.
 *
 * Nothing here is clever on purpose: the parent's assertions are about the one line printed,
 * so any extra output would be the test's own bug rather than the lock's.
 */
fun main(args: Array<String>) {
    val recordRoot = Path.of(args.first())
    val lock =
        try {
            RecordRepositoryLock(recordRoot)
        } catch (refused: RecordRepositoryLockedException) {
            report("${LockHolder.REFUSED} ${refused.message}")
            return
        }
    lock.use {
        report(LockHolder.ACQUIRED)
        // Blocks until the parent closes stdin — or kills us, which is the case that proves
        // the operating system releases the lock without our help.
        generateSequence(::readlnOrNull).firstOrNull()
    }
}

private fun report(line: String) {
    println(line)
    System.out.flush()
}
