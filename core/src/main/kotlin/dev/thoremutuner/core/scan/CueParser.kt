package dev.thoremutuner.core.scan

/** A FILE entry of a .cue sheet with the mode of its first TRACK. */
data class CueFile(val name: String, val trackMode: String?)

object CueParser {
    private val fileLine = Regex("^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))\\s*(\\S+)?\\s*$", RegexOption.IGNORE_CASE)
    private val trackLine = Regex("^\\s*TRACK\\s+\\d+\\s+(\\S+)\\s*$", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<CueFile> {
        val files = mutableListOf<CueFile>()
        var pendingName: String? = null
        var pendingMode: String? = null
        for (line in text.lineSequence()) {
            fileLine.find(line)?.let { m ->
                pendingName?.let { files += CueFile(it, pendingMode) }
                pendingName = m.groupValues[1].ifEmpty { m.groupValues[2] }
                pendingMode = null
                return@let
            }
            trackLine.find(line)?.let { m -> if (pendingMode == null) pendingMode = m.groupValues[1].uppercase() }
        }
        pendingName?.let { files += CueFile(it, pendingMode) }
        return files
    }
}

/** .m3u playlists (one relative path per line) and .gdi track lists. */
object PlaylistParser {
    fun m3u(text: String): List<String> =
        text.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

    /** GDI: first line = track count; then "n lba type sectorSize filename offset" (name may be quoted). */
    fun gdi(text: String): List<String> =
        text.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val quoted = Regex("\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            quoted ?: line.split(Regex("\\s+")).getOrNull(4)
        }.toList()
}
