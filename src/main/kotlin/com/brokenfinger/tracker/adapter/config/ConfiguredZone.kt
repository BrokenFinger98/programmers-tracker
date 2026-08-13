package com.brokenfinger.tracker.adapter.config

import java.time.ZoneId

/**
 * Resolves a configured time zone, falling back to **the clock the process is running on**.
 *
 * The fallback is the whole point. `tracker.backup.zone` shipped defaulting to `Asia/Seoul`
 * while the comment beside it said a developer's timezone must not become everyone's
 * (dev rules §9.1) — the rule was written and the default contradicted it. The zone now comes
 * from one place, the container's `TZ`, and this is the only setting that overrides it.
 *
 * That same `TZ` is what `Clock.systemDefaultZone()` gives [com.brokenfinger.tracker.application.RecordWriter],
 * so a record's `ts` and the daily backup's hour can no longer disagree about what day it is
 * (#243). Left unset the container runs in UTC, and the startup report says so out loud rather
 * than letting an attempt history quietly render nine hours off.
 *
 * Strict on a value that is present (dev rules §4): a mistyped zone throws at startup, where a
 * configuration error is still cheap, instead of becoming UTC and being discovered in a record.
 */
object ConfiguredZone {
    fun of(configured: String, fallback: ZoneId = ZoneId.systemDefault()): ZoneId {
        val name = configured.trim()
        if (name.isEmpty()) return fallback
        return ZoneId.of(name)
    }
}
