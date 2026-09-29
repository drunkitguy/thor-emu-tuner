package dev.thoremutuner.core.scan

import dev.thoremutuner.core.model.SystemId

/** Extension map (ES-DE es_systems.xml, PLAN section 6.4) and folder rules. */
object SystemClassifier {
    val ARCHIVES = setOf("zip", "7z")

    private val GC_WII_EXT = listOf("iso", "gcm", "ciso", "gcz", "rvz", "wia", "wbfs", "dol", "elf", "tgc", "wad", "m3u")

    val extensions: Map<SystemId, Set<String>> = mapOf(
        SystemId.GC to GC_WII_EXT.toSet(),
        SystemId.WII to GC_WII_EXT.toSet(),
        SystemId.PS2 to setOf("iso", "chd", "cso", "bin", "img", "m3u"),
        SystemId.PSX to setOf("cue", "bin", "chd", "pbp", "m3u", "img", "iso", "ecm"),
        SystemId.PSP to setOf("iso", "cso", "chd", "pbp"),
        SystemId.N3DS to setOf("3ds", "cci", "cxi", "cia", "3dsx", "zcci", "zcxi", "z3dsx"),
        SystemId.NDS to setOf("nds", "dsi"),
        SystemId.SWITCH to setOf("nsp", "xci", "nca", "nro", "nso"),
        SystemId.PSVITA to setOf("psvita"),
        SystemId.SNES to setOf("sfc", "smc"),
        SystemId.NES to setOf("nes", "fds", "unf", "unif"),
        SystemId.GBA to setOf("gba"),
        SystemId.GB to setOf("gb", "gbc"),
        SystemId.GBC to setOf("gb", "gbc"),
        SystemId.N64 to setOf("z64", "n64", "v64"),
        SystemId.GENESIS to setOf("md", "gen", "smd", "bin"),
        SystemId.MASTERSYSTEM to setOf("sms"),
        SystemId.GAMEGEAR to setOf("gg"),
        SystemId.DREAMCAST to setOf("chd", "gdi", "cdi", "cue"),
        SystemId.WINDOWS to setOf("desktop"),
    )

    /** Folders never descended into. */
    val SKIPPED_FOLDERS = setOf("bios", "media", "images", "videos", "manuals", "downloaded_media")

    /** Grouping files whose members are hidden from the library. */
    val GROUP_EXT = setOf("m3u", "cue", "gdi")

    fun ext(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** Candidate systems for an extension outside any system folder (archives never qualify). */
    fun systemsForExt(ext: String): List<SystemId> {
        if (ext in ARCHIVES) return emptyList()
        val list = extensions.filterValues { ext in it }.keys.toList()
        // .gb and .gbc are shared by both Game Boy systems; the extension decides outside a folder.
        if (list.toSet() == setOf(SystemId.GB, SystemId.GBC)) return listOf(if (ext == "gbc") SystemId.GBC else SystemId.GB)
        return list
    }

    /** Whether a file belongs in the library, given the system of its nearest system folder. */
    fun accepts(fileName: String, folderSystem: SystemId?): Boolean {
        val e = ext(fileName)
        if (e.isEmpty()) return false
        return if (folderSystem != null) {
            e in (extensions[folderSystem] ?: emptySet()) || e in ARCHIVES
        } else {
            systemsForExt(e).isNotEmpty()
        }
    }

    private val TAGS = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val SPACES = Regex("\\s+")

    /** Filename without extension and without (...) / [...] tags. */
    fun cleanTitle(fileName: String): String {
        val base = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
        return base.replace(TAGS, " ").replace(SPACES, " ").trim().trim('-', '_', ' ')
    }
}
