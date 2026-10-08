package com.brokenfinger.tracker.support.fixtures

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A clock a test moves by hand (dev rules §6.4), for what one instance does over several days: set [now]
 * to another instant and every reader of this clock sees it at once. It keeps UTC whatever it is asked.
 */
class MovableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}
