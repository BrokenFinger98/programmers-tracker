package com.brokenfinger.tracker.application

/**
 * Reads back the problem statement stored beside a problem's records (#278).
 *
 * An outbound port because the statement is a file and this layer knows no filesystem — the
 * same shape [RecordStore] has, and read-only for the same reason: an AI holding the MCP token
 * must not be able to alter a solving record however it is prompted
 * ([[decisions/2026-08-06-mcp-read-slice]]).
 *
 * The title comes along because it is part of the path: a problem directory is
 * `<lessonId>-<slug>`, and the slug is the title's.
 */
fun interface ProblemStatements {
    /** Null when nothing was captured — a page that carried none, or a record older than #275. */
    fun of(lessonId: Long, title: String?): String?
}
