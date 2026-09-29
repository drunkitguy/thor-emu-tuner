package dev.thoremutuner.app.data

import dev.thoremutuner.core.store.BackupPolicy
import java.io.File

/**
 * App-private copies of emulator config files taken before every write:
 * `filesDir/tuner/backups/<emulatorId>/<yyyyMMdd-HHmmss>/<relativePath>` (PLAN section 7.2).
 */
class BackupStore(private val tunerRoot: File) {

    /** Saves [text] and returns the backup's stamp (folder name). */
    @Synchronized
    fun save(emulatorId: String, relativePath: String, text: String, now: Long): String {
        val base = BackupPolicy.stamp(now)
        var stamp = base
        var n = 1
        while (dir(emulatorId, stamp).exists()) stamp = "$base-${n++}"
        val f = File(dir(emulatorId, stamp), relativePath)
        require(f.canonicalPath.startsWith(tunerRoot.canonicalPath)) { "invalid backup path" }
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        prune(emulatorId)
        return stamp
    }

    fun read(emulatorId: String, stamp: String, relativePath: String): String? =
        File(dir(emulatorId, stamp), relativePath).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    fun stamps(emulatorId: String): List<String> =
        File(tunerRoot, "${BackupPolicy.ROOT}/$emulatorId").list()?.sorted().orEmpty()

    private fun prune(emulatorId: String) {
        val doomed = BackupPolicy.toDelete(stamps(emulatorId), BackupPolicy.protectsFirst(emulatorId))
        doomed.forEach { dir(emulatorId, it).deleteRecursively() }
    }

    private fun dir(emulatorId: String, stamp: String) = File(tunerRoot, BackupPolicy.dir(emulatorId, stamp))
}
