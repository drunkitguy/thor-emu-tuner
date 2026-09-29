package dev.thoremutuner.core.launch

/**
 * Converts an ExternalStorageProvider document id to a filesystem path for emulators that need
 * legacy paths (RetroArch, Winlator): `primary:<p>` -> `/storage/emulated/0/<p>`,
 * `<UUID>:<p>` -> `/storage/<UUID>/<p>`. Anything else (other providers) is unsupported.
 */
object SafPaths {
    private val VOLUME_UUID = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    fun toFilePath(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val rel = documentId.substring(colon + 1).trimStart('/')
        if (rel.split('/').any { it == ".." }) return null
        return when {
            volume == "primary" -> "/storage/emulated/0/$rel".trimEnd('/')
            VOLUME_UUID.matches(volume) -> "/storage/${volume.uppercase()}/$rel".trimEnd('/')
            else -> null
        }
    }
}
