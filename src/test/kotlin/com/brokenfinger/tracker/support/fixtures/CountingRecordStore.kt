package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.RecordStore
import com.brokenfinger.tracker.application.RecordedSubmission

// A test double at the record-store port (dev rules §6.1), for tests that must show a call reads the log once
// and no more. The answer is the same however often the log was read, so the answer cannot show it; the port
// is the only seam.

/** A record store that answers from [real] and counts how many times the log was read through it. */
class CountingRecordStore(private val real: RecordStore) : RecordStore by real {
    var reads = 0
        private set

    override fun read(): List<RecordedSubmission> {
        reads++
        return real.read()
    }
}
