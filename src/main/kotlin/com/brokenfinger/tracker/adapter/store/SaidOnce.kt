package com.brokenfinger.tracker.adapter.store

import java.util.concurrent.ConcurrentHashMap

/**
 * What a writer or reader of the records says once (#386): each key at most once for this instance, however often its
 * reason comes back, so a refusal that stands does not drown every other line. `RecordWrites`, `RecordReads`,
 * `AtomicStateFile` and `FileRawSessionLog` each kept a set of their own for it.
 *
 * Per instance on purpose: a writer built again, or a server restarted, says it again, because whoever reads that log
 * has not seen it yet.
 */
internal class SaidOnce {
    private val said = ConcurrentHashMap.newKeySet<Any>()

    /** Runs [words] the first time [key] comes to this instance, and never after. */
    fun say(key: Any, words: () -> Unit) {
        if (said.add(key)) words()
    }
}
