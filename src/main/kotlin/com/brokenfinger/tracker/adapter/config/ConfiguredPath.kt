package com.brokenfinger.tracker.adapter.config

import java.nio.file.Path

/**
 * Resolves a configured path, expanding a leading `~` to the user's home directory.
 *
 * Only a **leading** tilde is expanded, and the expansion is a plain concatenation rather
 * than `replaceFirst`. Both details are load-bearing:
 *
 * - `replaceFirst("~", home)` rewrites a tilde anywhere in the string, and Windows short
 *   paths legitimately contain one — a temp directory reads
 *   `C:\Users\RUNNER~1\AppData\Local\Temp`. Expanding that tilde produced
 *   `C:\Users\RUNNERC:\Users\runneradmin1\AppData\…`, which Windows rejects. Measured in CI
 *   on 2026-08-06 (#41).
 * - `replaceFirst` also treats its replacement as a regex replacement, where a backslash is
 *   an escape character. Every Windows home directory is full of them.
 */
object ConfiguredPath {
    fun of(configured: String): Path {
        if (!configured.startsWith(HOME)) return Path.of(configured)
        return Path.of(System.getProperty("user.home") + configured.removePrefix(HOME))
    }

    private const val HOME = "~"
}
