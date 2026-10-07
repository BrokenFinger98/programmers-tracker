package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.application.RecordedSubmission

// Support for tests that must show a call reads the log once and no more (dev rules §6.4). The answer is
// the same however often the log was read, so the answer cannot show it; the port is the only seam.

/** A record store that answers from [real] and counts how many times the log was read through it. */
class CountingRecordStore(private val real: RecordStore) : RecordStore by real {
    var reads = 0
        private set

    override fun read(): List<RecordedSubmission> {
        reads++
        return real.read()
    }
}
