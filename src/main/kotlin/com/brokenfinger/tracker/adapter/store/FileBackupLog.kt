package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.BackupLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant

/**
 * File-backed [BackupLog] — `.ps/backup.json`, one ISO-8601 instant.
 *
 * Rewritten whole on every backup, so it goes through [AtomicStateFile] like every other state
 * document ([[decisions/2026-08-05-write-serialization]] decision 3).
 *
 * Reads are lenient in the same posture as protocol parsing: a document we cannot read reports
 * "never backed up", which makes the next start back up once too often rather than skip a day.
 * An unreadable file must not be the reason a week of attempts never leaves the machine.
 */
class FileBackupLog(private val file: AtomicStateFile) : BackupLog {
    override fun lastSuccessAt(): Instant? {
        val text = file.read() ?: return null
        return runCatching { stampOf(text)?.let(Instant::parse) }.getOrElse { unreadable() }
    }

    override fun succeededAt(instant: Instant) = file.write(Json.encodeToString(mapOf(LAST_SUCCESS to "$instant")))

    private fun stampOf(text: String): String? =
        Json.parseToJsonElement(text).jsonObject[LAST_SUCCESS]?.jsonPrimitive?.contentOrNull

    private fun unreadable(): Instant? {
        logger.warn("The backup state document is unreadable; treating the backup as never run")
        return null
    }

    companion object {
        private const val BACKUP = "backup.json"
        private const val LAST_SUCCESS = "lastSuccessAt"

        private val logger = LoggerFactory.getLogger(FileBackupLog::class.java)

        /** Backup state lives under the record repository with the other state (design §5.1). */
        fun under(recordRoot: Path): FileBackupLog = FileBackupLog(AtomicStateFile.under(recordRoot, BACKUP))
    }
}
