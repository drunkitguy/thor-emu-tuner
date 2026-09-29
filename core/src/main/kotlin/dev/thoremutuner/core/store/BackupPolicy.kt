package dev.thoremutuner.core.store

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Backups live in `backups/<emulatorId>/<yyyyMMdd-HHmmss>/<relativePath>` (PLAN section 7.2). The
 * newest [KEEP] per emulator are kept, plus the first-ever Azahar `config.ini` backup, which is
 * never deleted.
 */
object BackupPolicy {
    const val KEEP = 20
    const val ROOT = "backups"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    private val STAMP_RE = Regex("^\\d{8}-\\d{6}(-\\d+)?$")

    fun stamp(epochMillis: Long): String = STAMP.format(Instant.ofEpochMilli(epochMillis))

    fun dir(emulatorId: String, stamp: String): String = "$ROOT/$emulatorId/$stamp"

    /** Backup folder names (stamps) to delete; the oldest one is protected when [protectFirst] is set. */
    fun toDelete(stamps: List<String>, protectFirst: Boolean): List<String> {
        val sorted = stamps.filter { STAMP_RE.matches(it) }.sorted()
        val first = if (protectFirst) sorted.firstOrNull() else null
        return sorted.filter { it != first }.dropLast(KEEP)
    }

    /** Only Azahar edits a global file whose original must survive forever. */
    fun protectsFirst(emulatorId: String): Boolean = emulatorId == "azahar"
}
