package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.ProblemStatements
import java.nio.file.Files

/**
 * The statement file, read through the layout that wrote it.
 *
 * Never throws. A statement is the one thing on this surface a reader can do without, so an
 * unreadable file answers "absent" and the rest of `get_problem` still arrives — the same
 * leniency the record reader takes towards a torn final line.
 */
class FileProblemStatements(private val layout: RecordLayout) : ProblemStatements {
    override fun of(lessonId: Long, title: String?): String? =
        runCatching { Files.readString(layout.statementFile(lessonId, title)).trim().ifEmpty { null } }.getOrNull()
}
