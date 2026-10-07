package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.ProblemStatements

/**
 * The statement file, read through the layout that wrote it.
 *
 * Never throws. A statement is the one thing on this surface a reader can do without, so an
 * unreadable file answers "absent" and the rest of `get_problem` still arrives — the same
 * leniency the record reader takes towards a torn final line.
 *
 * Read through [ProblemFiles], because it leaves the machine twice — served by `get_problem` and
 * written into the problem page that is pushed. A `statement.md` that is a link out of `problems/`
 * is absent, however it arrived: this reader handed the push token to `get_problem` before (#354).
 */
class FileProblemStatements(private val layout: RecordLayout) : ProblemStatements {
    private val files = ProblemFiles(layout)

    override fun of(lessonId: Long, title: String?): String? =
        runCatching { files.readString(layout.statementFile(lessonId, title))?.trim()?.ifEmpty { null } }.getOrNull()
}
